package com.socialapp.friendships.service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.friendships.cache.FriendSuggestionCache;
import com.socialapp.friendships.cache.UserProfileCache;
import com.socialapp.friendships.dto.FriendListResponseDto;
import com.socialapp.friendships.dto.FriendRequestPageResponseDto;
import com.socialapp.friendships.dto.FriendSuggestionDto;
import com.socialapp.friendships.dto.MutualFriendCountDto;
import com.socialapp.friendships.dto.PendingFriendRequestDto;
import com.socialapp.friendships.dto.SentFriendRequestDto;
import com.socialapp.friendships.dto.SentFriendRequestPageResponseDto;
import com.socialapp.friendships.dto.UserProfileDto;
import com.socialapp.friendships.entity.FriendRequestEntity;
import com.socialapp.friendships.entity.enums.FriendRequestStatus;
import com.socialapp.friendships.repository.FriendRequestRepository;
import com.socialapp.friendships.repository.FriendshipRepository;
import com.socialapp.hashtags.dto.AuthorHashtagDto;
import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.entity.enums.PrimaryRole;
import com.socialapp.knowledge.repository.UserProfessionalProfileRepository;
import com.socialapp.knowledge.service.ProfileMatchScorer;
import com.socialapp.notifications.NotificationMessages;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.posts.repository.HashtagRepository;
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
  private final HashtagRepository hashtagRepository;

  /**
   * Candidate pool pulled from Neo4j before background-based re-ranking and trimming to the
   * caller's requested limit; decoupled from that limit so the cached pool stays reusable.
   */
  private static final int SUGGESTION_POOL_SIZE = 50;

  /**
   * How many shared hashtags a single suggestion carries as a reason. A prolific poster can share
   * dozens of tags with someone; the point is a recognisable "you both post about X", not a second
   * profile page, so the list is capped the way {@code matchedSkills} isn't (tech stacks are
   * user-curated and short by construction, tag history across every post someone has ever written
   * is not).
   */
  private static final int MAX_SHARED_HASHTAGS = 5;

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
            .messageKey(NotificationMessages.FRIEND_REQUEST)
            .messageArgs(NotificationMessages.args("actor", actorName(actorId)))
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

    // The graph write happens AFTER Postgres commits, not alongside it.
    //
    // These are two stores with two transaction managers and no shared transaction: JPA is
    // @Primary and owns this method, while FriendshipRepository is a Neo4jRepository wired to
    // neo4jTransactionManager (see Neo4jConfig). Written inline, the Cypher committed immediately
    // and the row committed at the end of the method, so anything failing in between — the
    // notification below, a constraint, a dropped connection — left the graph saying the two are
    // friends while the request sat PENDING. That is the dangerous direction: getFriends, the
    // suggestion query and the fan-out audience all read the graph, so the pair would see each
    // other's FRIENDS-only posts off the back of a request nobody ever accepted, and unfriend
    // could not clean it up because deleteAcceptedBetween would find no row.
    //
    // Deferring inverts the skew. If the graph write fails now, Postgres says ACCEPTED and the
    // graph has no edge: the two are not yet friends anywhere it matters, nobody sees anything
    // they should not, and re-accepting or a reconciliation pass repairs it. Same afterCommit
    // pattern as BlockService, and for the same reason.
    Integer requesterId = request.getRequesterId();
    Integer addresseeId = request.getAddresseeId();
    afterCommit(
        () -> {
          friendshipRepository.mergeUser(requesterId);
          friendshipRepository.mergeUser(addresseeId);
          friendshipRepository.createFriendship(requesterId, addresseeId);
        });

    friendSuggestionCache.evict(request.getRequesterId());
    friendSuggestionCache.evict(request.getAddresseeId());

    notificationService.send(
        SendNotificationRequest.builder()
            .recipientId(request.getRequesterId())
            .actorId(actorId)
            .type(NotificationType.FRIEND_ACCEPTED)
            .title("Friend request accepted")
            .body(actorName(actorId) + " accepted your friend request")
            .messageKey(NotificationMessages.FRIEND_ACCEPTED)
            .messageArgs(NotificationMessages.args("actor", actorName(actorId)))
            .referenceId(request.getId())
            .referenceType("FRIEND_REQUEST")
            .build());
  }

  /**
   * Runs {@code action} once the surrounding transaction has committed, or immediately when there
   * is none.
   *
   * <p>Same helper, and the same reasoning, as {@code BlockService#afterCommit}: work that reaches
   * a second store must not be done speculatively inside a transaction that may still roll back.
   * Kept local rather than shared because the two modules have no dependency on one another and one
   * short method is a smaller cost than a new coupling.
   */
  private void afterCommit(Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      action.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.run();
          }
        });
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
  public FriendRequestPageResponseDto getPendingRequests(
      Integer userId, Integer cursor, int limit) {
    // limit + 1 to answer hasMore without a COUNT, and the cursor is taken from the last row READ
    // rather than the last one kept — block filtering happens after, and a cursor taken after it
    // would rewind whenever the last request on a page came from a blocked user.
    List<FriendRequestEntity> rows =
        friendRequestRepository.findIncomingForPage(
            userId, FriendRequestStatus.PENDING, cursor, PageRequest.of(0, limit + 1));

    boolean hasMore = rows.size() > limit;
    List<FriendRequestEntity> page = hasMore ? rows.subList(0, limit) : rows;
    Integer nextCursor = hasMore ? page.get(page.size() - 1).getId() : null;

    Set<Integer> blockedIds = blockQueryService.blockedPairIds(userId);
    List<FriendRequestEntity> requests =
        page.stream().filter(request -> !blockedIds.contains(request.getRequesterId())).toList();

    Map<Integer, UserProfileDto> profilesById =
        loadProfiles(
            requests.stream().map(FriendRequestEntity::getRequesterId).collect(Collectors.toSet()));

    List<PendingFriendRequestDto> dtos =
        requests.stream()
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

    return new FriendRequestPageResponseDto(dtos, nextCursor, hasMore);
  }

  public SentFriendRequestPageResponseDto getSentRequests(
      Integer userId, Integer cursor, int limit) {
    List<FriendRequestEntity> rows =
        friendRequestRepository.findOutgoingForPage(
            userId, FriendRequestStatus.PENDING, cursor, PageRequest.of(0, limit + 1));

    boolean hasMore = rows.size() > limit;
    List<FriendRequestEntity> requests = hasMore ? rows.subList(0, limit) : rows;
    Integer nextCursor = hasMore ? requests.get(requests.size() - 1).getId() : null;

    Map<Integer, UserProfileDto> profilesById =
        loadProfiles(
            requests.stream().map(FriendRequestEntity::getAddresseeId).collect(Collectors.toSet()));

    List<SentFriendRequestDto> dtos =
        requests.stream()
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

    return new SentFriendRequestPageResponseDto(dtos, nextCursor, hasMore);
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

    // Skipped entirely on an empty pool rather than left to rankByBackground's own guard: nothing
    // downstream needs it, and a suggestions list a caller will never see is not worth a query.
    UserProfessionalProfileEntity callerProfile =
        visiblePool.isEmpty() ? null : professionalProfileRepository.findById(userId).orElse(null);

    List<MutualFriendCountDto> topSuggestions =
        rankByBackground(callerProfile, visiblePool).stream().limit(limit).toList();

    // Everything from here on is about explaining the already-decided order, not changing it —
    // computed only for the trimmed page, not the up-to-50-wide pool rankByBackground sorted.
    Set<Integer> suggestedIds =
        topSuggestions.stream().map(MutualFriendCountDto::userId).collect(Collectors.toSet());
    Map<Integer, UserProfileDto> profilesById = loadProfiles(suggestedIds);
    Map<Integer, UserProfessionalProfileEntity> candidateProfiles =
        professionalProfilesById(suggestedIds);
    Map<Integer, List<String>> sharedHashtagsById = sharedHashtagsByCandidate(userId, suggestedIds);

    return topSuggestions.stream()
        .map(
            s -> {
              UserProfileDto profile = profilesById.get(s.userId());
              if (profile == null) {
                return null;
              }
              UserProfessionalProfileEntity candidate = candidateProfiles.get(s.userId());
              return new FriendSuggestionDto(
                  profile,
                  s.mutualFriends(),
                  sharedRole(callerProfile, candidate),
                  matchedSkills(callerProfile, candidate),
                  sharedHashtagsById.getOrDefault(s.userId(), List.of()));
            })
        .filter(Objects::nonNull)
        .toList();
  }

  /**
   * Re-ranks the mutual-friends-ranked candidate pool so people sharing the caller's professional
   * background (primary role, then tech stack overlap) surface first, falling back to mutual
   * friend count as the tiebreaker. If the caller hasn't set up a professional profile, the pool
   * is returned unchanged (already ranked by mutual friends from Neo4j).
   */
  private List<MutualFriendCountDto> rankByBackground(
      UserProfessionalProfileEntity callerProfile, List<MutualFriendCountDto> pool) {
    if (pool.isEmpty() || callerProfile == null) {
      return pool;
    }

    Set<Integer> candidateIds =
        pool.stream().map(MutualFriendCountDto::userId).collect(Collectors.toSet());
    Map<Integer, UserProfessionalProfileEntity> candidateProfiles =
        professionalProfilesById(candidateIds);

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

  private Map<Integer, UserProfessionalProfileEntity> professionalProfilesById(
      Set<Integer> userIds) {
    if (userIds.isEmpty()) {
      return Map.of();
    }
    return professionalProfileRepository.findAllById(userIds).stream()
        .collect(Collectors.toMap(UserProfessionalProfileEntity::getUserId, Function.identity()));
  }

  // The four helpers below are null-safe on BOTH sides: rankByBackground only ever calls them
  // with a non-null caller (guarded above), but getSuggestions also uses sameRole/matchedSkills
  // to build the reason on a caller that may have no professional profile at all — in which case
  // "same background" is simply not one of the reasons, not a NullPointerException.
  private boolean sameRole(
      UserProfessionalProfileEntity caller, UserProfessionalProfileEntity candidate) {
    return caller != null
        && candidate != null
        && ProfileMatchScorer.sameRole(caller.getPrimaryRole(), candidate.getPrimaryRole());
  }

  /** The caller's own {@code primaryRole} when it matches the candidate's, {@code null} otherwise. */
  private PrimaryRole sharedRole(
      UserProfessionalProfileEntity caller, UserProfessionalProfileEntity candidate) {
    return sameRole(caller, candidate) ? caller.getPrimaryRole() : null;
  }

  private int techStackOverlap(
      UserProfessionalProfileEntity caller, UserProfessionalProfileEntity candidate) {
    return caller == null || candidate == null
        ? 0
        : ProfileMatchScorer.skillOverlap(
            caller.getKnownTechStack(), candidate.getKnownTechStack());
  }

  private List<String> matchedSkills(
      UserProfessionalProfileEntity caller, UserProfessionalProfileEntity candidate) {
    return caller == null || candidate == null
        ? List.of()
        : ProfileMatchScorer.matchedSkills(
            caller.getKnownTechStack(), candidate.getKnownTechStack());
  }

  /**
   * For each id in {@code candidateIds}, the hashtags {@code userId} and that candidate have both
   * carried on a PUBLIC, APPROVED post, ordered by how popular the tag is overall and capped at
   * {@link #MAX_SHARED_HASHTAGS}. A candidate with no overlap — including one who has never
   * posted publicly, or hasn't used any tag the caller has — is absent from the map rather than
   * mapped to an empty list, so callers can use {@code getOrDefault(id, List.of())} either way.
   *
   * <p>Independent of professional-profile data: this is the one reason that still works for a
   * caller who has never filled in {@code UserProfessionalProfileEntity}, because it is built
   * from what people actually posted rather than what they declared about themselves.
   */
  private Map<Integer, List<String>> sharedHashtagsByCandidate(
      Integer userId, Set<Integer> candidateIds) {
    if (candidateIds.isEmpty()) {
      return Map.of();
    }

    Set<Integer> authorIds = new HashSet<>(candidateIds);
    authorIds.add(userId);
    Map<Integer, List<String>> hashtagsByAuthor =
        hashtagRepository.findHashtagsByAuthors(authorIds).stream()
            .collect(
                Collectors.groupingBy(
                    AuthorHashtagDto::authorId,
                    Collectors.mapping(AuthorHashtagDto::hashtagName, Collectors.toList())));

    Set<String> callerTags = Set.copyOf(hashtagsByAuthor.getOrDefault(userId, List.of()));
    if (callerTags.isEmpty()) {
      return Map.of();
    }

    Map<Integer, List<String>> shared = new LinkedHashMap<>();
    for (Integer candidateId : candidateIds) {
      List<String> candidateTags = hashtagsByAuthor.get(candidateId);
      if (candidateTags == null) {
        continue;
      }
      List<String> overlap =
          candidateTags.stream()
              .filter(callerTags::contains)
              .distinct()
              .limit(MAX_SHARED_HASHTAGS)
              .toList();
      if (!overlap.isEmpty()) {
        shared.put(candidateId, overlap);
      }
    }
    return shared;
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
