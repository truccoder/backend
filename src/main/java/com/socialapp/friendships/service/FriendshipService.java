package com.socialapp.friendships.service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.friendships.cache.FriendSuggestionCache;
import com.socialapp.friendships.cache.UserProfileCache;
import com.socialapp.friendships.dto.FriendListResponseDto;
import com.socialapp.friendships.dto.FriendSuggestionDto;
import com.socialapp.friendships.dto.MutualFriendCountDto;
import com.socialapp.friendships.dto.PendingFriendRequestDto;
import com.socialapp.friendships.dto.SentFriendRequestDto;
import com.socialapp.friendships.dto.UserProfileDto;
import com.socialapp.friendships.entity.FriendRequestEntity;
import com.socialapp.friendships.entity.enums.FriendRequestStatus;
import com.socialapp.friendships.repository.FriendRequestRepository;
import com.socialapp.friendships.repository.FriendshipRepository;
import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.repository.UserProfessionalProfileRepository;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FriendshipService {
  private final FriendRequestRepository friendRequestRepository;
  private final FriendshipRepository friendshipRepository;
  private final UserRepository userRepository;
  private final NotificationService notificationService;
  private final UserProfileCache userProfileCache;
  private final FriendSuggestionCache friendSuggestionCache;
  private final UserProfessionalProfileRepository professionalProfileRepository;
  private final Neo4jClient neo4jClient;
  private final BlockQueryService blockQueryService;

  /**
   * Candidate pool pulled from Neo4j before background-based re-ranking and trimming to the
   * caller's requested limit; decoupled from that limit so the cached pool stays reusable.
   */
  private static final int SUGGESTION_POOL_SIZE = 50;

  @Transactional
  public void sendFriendRequest(Integer actorId, Integer addresseeId) {
    if (actorId.equals(addresseeId)) {
      throw new ValidationException("You cannot send a friend request to yourself");
    }
    if (!userRepository.existsById(addresseeId)) {
      throw new NotFoundException("User not found with ID: " + addresseeId);
    }
    // Refused across a block, in either direction.
    //
    // Blocking already ends the friendship and cancels whatever request was in flight at the time
    // — but nothing stopped the blocked user from simply sending a new one. The row would be
    // written, and while the notification is suppressed elsewhere, the request itself still landed
    // in the blocker's pending list: their name back in front of the person who blocked them, as
    // often as they cared to click. That is the thing blocking exists to prevent.
    //
    // The message deliberately does not say "you are blocked". It cannot hide that the request was
    // refused — silently accepting it would either store a request that can never be accepted or
    // lie to the sender about what happened — but it does not have to confirm why.
    if (blockQueryService.isBlockedEitherWay(actorId, addresseeId)) {
      throw new ValidationException("You cannot send a friend request to this user");
    }
    if (friendshipRepository.areFriends(actorId, addresseeId)) {
      throw new ValidationException("You are already friends with this user");
    }
    if (friendRequestRepository.hasPendingRequestBetween(actorId, addresseeId)) {
      throw new ValidationException("A pending friend request already exists between these users");
    }

    FriendRequestEntity entity = new FriendRequestEntity();
    entity.setRequesterId(actorId);
    entity.setAddresseeId(addresseeId);
    entity.setStatus(FriendRequestStatus.PENDING);

    // The check above is a plain read then this insert, so it's still possible for two
    // near-simultaneous requests between the same pair to both pass it. saveAndFlush() (rather
    // than save()) forces the insert - and the uq_friend_requests_pending_pair constraint it
    // may violate - to happen synchronously here, so the race is caught with the same friendly
    // message instead of surfacing as a raw 500 from a later, unrelated flush.
    try {
      friendRequestRepository.saveAndFlush(entity);
    } catch (DataIntegrityViolationException e) {
      throw new ValidationException("A pending friend request already exists between these users");
    }

    notificationService.send(
        SendNotificationRequest.builder()
            .recipientId(addresseeId)
            .actorId(actorId)
            .type(NotificationType.FRIEND_REQUEST)
            .title("New friend request")
            .body(actorName(actorId) + " sent you a friend request")
            .referenceId(entity.getId())
            .referenceType("FRIEND_REQUEST")
            .build());
  }

  @Transactional
  public void cancelFriendRequest(Integer actorId, Integer requestId) {
    FriendRequestEntity request = findPendingRequestOrThrow(requestId);

    if (!request.getRequesterId().equals(actorId)) {
      throw new ForbiddenException("Only the requester can cancel this friend request");
    }

    request.setStatus(FriendRequestStatus.CANCELLED);
    friendRequestRepository.save(request);
  }

  @Transactional
  public void acceptFriendRequest(Integer actorId, Integer requestId) {
    FriendRequestEntity request = findPendingRequestOrThrow(requestId);

    if (!request.getAddresseeId().equals(actorId)) {
      throw new ForbiddenException("Only the addressee can accept this friend request");
    }

    request.setStatus(FriendRequestStatus.ACCEPTED);
    friendRequestRepository.save(request);

    friendshipRepository.mergeUser(request.getRequesterId());
    friendshipRepository.mergeUser(request.getAddresseeId());
    friendshipRepository.createFriendship(request.getRequesterId(), request.getAddresseeId());

    friendSuggestionCache.evict(request.getRequesterId());
    friendSuggestionCache.evict(request.getAddresseeId());

    notificationService.send(
        SendNotificationRequest.builder()
            .recipientId(request.getRequesterId())
            .actorId(actorId)
            .type(NotificationType.FRIEND_ACCEPTED)
            .title("Friend request accepted")
            .body(actorName(actorId) + " accepted your friend request")
            .referenceId(request.getId())
            .referenceType("FRIEND_REQUEST")
            .build());
  }

  @Transactional
  public void rejectFriendRequest(Integer actorId, Integer requestId) {
    FriendRequestEntity request = findPendingRequestOrThrow(requestId);

    if (!request.getAddresseeId().equals(actorId)) {
      throw new ForbiddenException("Only the addressee can reject this friend request");
    }

    request.setStatus(FriendRequestStatus.REJECTED);
    friendRequestRepository.save(request);
  }

  /**
   * Ends a friendship.
   *
   * <p>Until now there was no way back out of one: {@code acceptFriendRequest} wrote to Postgres
   * <i>and</i> Neo4j and nothing undid either, so an unwanted friend could only be removed with
   * hand-written SQL and Cypher.
   *
   * <p><b>Both stores, always.</b> The graph holds the friendship itself (it is what {@code
   * getFriends}, the suggestion query and the fan-out audience all read); the accepted request row
   * in Postgres is the record that produced it. Clearing one and not the other leaves the pair
   * friends according to whichever store was missed.
   *
   * <p><b>Idempotent.</b> Calling it when the two are not friends is a no-op, not a 404: the
   * caller asked for a state ("we are not friends") that is already true, and a client retrying
   * after a dropped response must not get an error for succeeding twice.
   */
  @Transactional
  public void unfriend(Integer actorId, Integer otherUserId) {
    if (actorId.equals(otherUserId)) {
      throw new ValidationException("You cannot unfriend yourself");
    }

    friendshipRepository.deleteFriendship(actorId, otherUserId);
    friendRequestRepository.deleteAcceptedBetween(actorId, otherUserId);

    // Both suggestion pools are now wrong in the same way: each of these two people is once again
    // a candidate for the other, and their mutual-friend counts have changed.
    friendSuggestionCache.evict(actorId);
    friendSuggestionCache.evict(otherUserId);
  }

  public FriendListResponseDto getFriends(Integer userId, Integer cursor, int limit) {
    List<Integer> friendIds =
        friendshipRepository.findFriendIdsAfterCursor(userId, cursor, limit + 1);

    boolean hasMore = friendIds.size() > limit;
    List<Integer> pageIds = hasMore ? friendIds.subList(0, limit) : friendIds;
    Integer nextCursor = hasMore ? pageIds.get(pageIds.size() - 1) : null;

    Map<Integer, UserProfileDto> profilesById = loadProfiles(Set.copyOf(pageIds));
    List<UserProfileDto> friends =
        pageIds.stream().map(profilesById::get).filter(Objects::nonNull).toList();

    return new FriendListResponseDto(
        friends, nextCursor, hasMore, friendshipRepository.countFriends(userId));
  }

  /**
   * Every friend id, unpaginated and without the profile lookups {@link #getFriends} does. Exists
   * so other modules (chat) can fan out over a user's friends without reaching into this module's
   * Neo4j repository directly.
   */
  public List<Integer> getFriendIds(Integer userId) {
    return friendshipRepository.findFriendIds(userId);
  }

  /**
   * Whether two users are friends, as one graph lookup.
   *
   * <p>Exists alongside {@link #getFriendIds} so a caller asking about a single pair — the post
   * permalink, the visibility check on one author — does not have to pull that user's entire
   * friend list and scan it. Same reason as {@link #getFriendIds} for living here rather than in
   * the callers: nothing outside this module should touch the Neo4j repository.
   */
  public boolean areFriends(Integer userId, Integer otherUserId) {
    return friendshipRepository.areFriends(userId, otherUserId);
  }

  /**
   * The friend requests waiting for this user's answer.
   *
   * <p>Filtered by the block set as well, even though {@code sendFriendRequest} now refuses to
   * create such a request and {@code block()} cancels the ones in flight. This is the reader-side
   * half of the same rule, and it is worth having twice: rows written before those two guards
   * existed are still sitting in databases that have been running the previous build, and this is
   * the screen where such a row would put a blocked user's name back in front of the person who
   * blocked them.
   *
   * <p>The sent-requests list needs no equivalent: blocking cancels pending requests in both
   * directions, so a request the caller sent to someone who then blocked them is already gone.
   */
  public List<PendingFriendRequestDto> getPendingRequests(Integer userId) {
    Set<Integer> blockedIds = blockQueryService.blockedPairIds(userId);
    List<FriendRequestEntity> requests =
        friendRequestRepository
            .findByAddresseeIdAndStatusOrderByCreatedAtDesc(userId, FriendRequestStatus.PENDING)
            .stream()
            .filter(request -> !blockedIds.contains(request.getRequesterId()))
            .toList();

    Map<Integer, UserProfileDto> profilesById =
        loadProfiles(
            requests.stream().map(FriendRequestEntity::getRequesterId).collect(Collectors.toSet()));

    return requests.stream()
        .map(
            request -> {
              UserProfileDto profile = profilesById.get(request.getRequesterId());
              return new PendingFriendRequestDto(
                  request.getId(),
                  request.getRequesterId(),
                  profile != null ? profile.fullName() : null,
                  profile != null ? profile.profilePictureUrl() : null,
                  request.getStatus(),
                  request.getCreatedAt());
            })
        .toList();
  }

  public List<SentFriendRequestDto> getSentRequests(Integer userId) {
    List<FriendRequestEntity> requests =
        friendRequestRepository.findByRequesterIdAndStatusOrderByCreatedAtDesc(
            userId, FriendRequestStatus.PENDING);

    Map<Integer, UserProfileDto> profilesById =
        loadProfiles(
            requests.stream().map(FriendRequestEntity::getAddresseeId).collect(Collectors.toSet()));

    return requests.stream()
        .map(
            request -> {
              UserProfileDto profile = profilesById.get(request.getAddresseeId());
              return new SentFriendRequestDto(
                  request.getId(),
                  request.getAddresseeId(),
                  profile != null ? profile.fullName() : null,
                  profile != null ? profile.profilePictureUrl() : null,
                  request.getStatus(),
                  request.getCreatedAt());
            })
        .toList();
  }

  public List<FriendSuggestionDto> getSuggestions(Integer userId, int limit) {
    // Was previously disabled (commented out), which made the evict() calls in
    // acceptFriendRequest()/rejectFriendRequest() dead code — nothing was ever cached to evict.
    List<MutualFriendCountDto> pool =
        friendSuggestionCache.getOrLoad(
            userId, () -> findFriendSuggestions(userId, SUGGESTION_POOL_SIZE));

    // Filtered here rather than inside the Cypher: the pool is cached, and a block placed after
    // it was cached would otherwise keep suggesting that person until the entry expired. This also
    // keeps one cached pool valid for the whole ranking pipeline. Blocking is two-way for
    // filtering, so this drops both people the caller blocked and people who blocked the caller —
    // suggesting someone who blocked you is a particularly bad way to find out.
    Set<Integer> blockedIds = blockQueryService.blockedPairIds(userId);
    List<MutualFriendCountDto> visiblePool =
        blockedIds.isEmpty()
            ? pool
            : pool.stream().filter(s -> !blockedIds.contains(s.userId())).toList();

    List<MutualFriendCountDto> topSuggestions =
        rankByBackground(userId, visiblePool).stream().limit(limit).toList();

    Set<Integer> suggestedIds =
        topSuggestions.stream().map(MutualFriendCountDto::userId).collect(Collectors.toSet());
    Map<Integer, UserProfileDto> profilesById = loadProfiles(suggestedIds);

    return topSuggestions.stream()
        .map(s -> new FriendSuggestionDto(profilesById.get(s.userId()), s.mutualFriends()))
        .filter(dto -> dto.profile() != null)
        .toList();
  }

  /**
   * Re-ranks the mutual-friends-ranked candidate pool so people sharing the caller's professional
   * background (primary role, then tech stack overlap) surface first, falling back to mutual
   * friend count as the tiebreaker. If the caller hasn't set up a professional profile, the pool
   * is returned unchanged (already ranked by mutual friends from Neo4j).
   */
  private List<MutualFriendCountDto> rankByBackground(
      Integer userId, List<MutualFriendCountDto> pool) {
    if (pool.isEmpty()) {
      return pool;
    }

    UserProfessionalProfileEntity callerProfile =
        professionalProfileRepository.findById(userId).orElse(null);
    if (callerProfile == null) {
      return pool;
    }

    Set<Integer> candidateIds =
        pool.stream().map(MutualFriendCountDto::userId).collect(Collectors.toSet());
    Map<Integer, UserProfessionalProfileEntity> candidateProfiles =
        professionalProfileRepository.findAllById(candidateIds).stream()
            .collect(
                Collectors.toMap(UserProfessionalProfileEntity::getUserId, Function.identity()));

    Comparator<MutualFriendCountDto> comparator =
        Comparator.comparing(
                (MutualFriendCountDto s) ->
                    sameRole(callerProfile, candidateProfiles.get(s.userId())))
            .reversed()
            .thenComparing(
                Comparator.comparingInt(
                        (MutualFriendCountDto s) ->
                            techStackOverlap(callerProfile, candidateProfiles.get(s.userId())))
                    .reversed())
            .thenComparing(Comparator.comparingLong(MutualFriendCountDto::mutualFriends).reversed())
            .thenComparing(MutualFriendCountDto::userId);

    return pool.stream().sorted(comparator).toList();
  }

  // Both helpers below are only ever called from rankByBackground() with an already
  // null-checked `caller` (see the `callerProfile == null` guard above), so `caller` itself is
  // never null here.
  private boolean sameRole(
      UserProfessionalProfileEntity caller, UserProfessionalProfileEntity candidate) {
    return candidate != null
        && caller.getPrimaryRole() != null
        && caller.getPrimaryRole().equals(candidate.getPrimaryRole());
  }

  private int techStackOverlap(
      UserProfessionalProfileEntity caller, UserProfessionalProfileEntity candidate) {
    if (candidate == null
        || caller.getKnownTechStack() == null
        || candidate.getKnownTechStack() == null) {
      return 0;
    }

    Set<String> callerStack =
        caller.getKnownTechStack().stream().map(String::toLowerCase).collect(Collectors.toSet());
    return (int)
        candidate.getKnownTechStack().stream()
            .map(String::toLowerCase)
            .filter(callerStack::contains)
            .count();
  }

  /**
   * Runs via {@link Neo4jClient} with manual row mapping instead of a Neo4jRepository interface
   * projection: Spring Data Neo4j's interface projections for custom @Query results always fall
   * back to reflectively reading properties off the repository's domain type (UserNode) for any
   * getter it can't resolve as a graph property, which throws for every aggregate column here
   * (e.g. mutualFriends) regardless of how the Cypher RETURN aliases are named.
   */
  private List<MutualFriendCountDto> findFriendSuggestions(Integer userId, int limit) {
    return neo4jClient
        .query(
            """
                                MATCH (u:User {userId: $userId})-[:FRIENDS_WITH]-(mutual:User)-[:FRIENDS_WITH]-(suggestion:User)
                                WHERE suggestion.userId <> $userId
                                  AND NOT (u)-[:FRIENDS_WITH]-(suggestion)
                                RETURN suggestion.userId AS candidateId, COUNT(DISTINCT mutual) AS mutualFriends
                                ORDER BY mutualFriends DESC, candidateId ASC
                                LIMIT $limit
                                """)
        .bind(userId)
        .to("userId")
        .bind(limit)
        .to("limit")
        .fetchAs(MutualFriendCountDto.class)
        .mappedBy(
            (typeSystem, record) ->
                new MutualFriendCountDto(
                    record.get("candidateId").asInt(), record.get("mutualFriends").asLong()))
        .all()
        .stream()
        .toList();
  }

  private Map<Integer, UserProfileDto> loadProfiles(Set<Integer> userIds) {
    if (userIds.isEmpty()) {
      return Map.of();
    }

    return userProfileCache.getOrLoadAll(
        userIds,
        missingIds -> {
          Map<Integer, UserProfileDto> loaded = new LinkedHashMap<>();
          userRepository
              .findAllById(missingIds)
              .forEach(
                  user ->
                      loaded.put(
                          user.getId(),
                          new UserProfileDto(
                              user.getId(),
                              user.getUsername(),
                              user.getFullName(),
                              user.getProfilePictureUrl())));
          return loaded;
        });
  }

  private FriendRequestEntity findPendingRequestOrThrow(Integer requestId) {
    FriendRequestEntity request =
        friendRequestRepository
            .findById(requestId)
            .orElseThrow(
                () -> new NotFoundException("Friend request not found with ID: " + requestId));

    if (request.getStatus() != FriendRequestStatus.PENDING) {
      throw new ValidationException(
          "Friend request is no longer pending (current status: " + request.getStatus() + ")");
    }
    return request;
  }

  private String actorName(Integer userId) {
    return userRepository
        .findById(userId)
        .map(UserEntity::getFullName)
        .filter(name -> !name.isBlank())
        .orElse("Someone");
  }
}
