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

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.friendships.cache.FriendSuggestionCache;
import com.socialapp.friendships.cache.UserProfileCache;
import com.socialapp.friendships.dto.FriendListResponseDto;
import com.socialapp.friendships.dto.FriendSuggestionDto;
import com.socialapp.friendships.dto.MutualFriendCountDto;
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

  public List<FriendSuggestionDto> getSuggestions(Integer userId, int limit) {
    // Was previously disabled (commented out), which made the evict() calls in
    // acceptFriendRequest()/rejectFriendRequest() dead code — nothing was ever cached to evict.
    List<MutualFriendCountDto> pool =
        friendSuggestionCache.getOrLoad(
            userId, () -> findFriendSuggestions(userId, SUGGESTION_POOL_SIZE));

    List<MutualFriendCountDto> topSuggestions =
        rankByBackground(userId, pool).stream().limit(limit).toList();

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

  private boolean sameRole(
      UserProfessionalProfileEntity caller, UserProfessionalProfileEntity candidate) {
    return caller != null
        && candidate != null
        && caller.getPrimaryRole() != null
        && caller.getPrimaryRole().equals(candidate.getPrimaryRole());
  }

  private int techStackOverlap(
      UserProfessionalProfileEntity caller, UserProfessionalProfileEntity candidate) {
    if (caller == null
        || candidate == null
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
        .filter(name -> name != null && !name.isBlank())
        .orElse("Someone");
  }
}
