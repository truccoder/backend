package com.socialapp.friendships.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.neo4j.core.Neo4jClient;

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
import com.socialapp.friendships.dto.UserProfileDto;
import com.socialapp.friendships.entity.FriendRequestEntity;
import com.socialapp.friendships.entity.enums.FriendRequestStatus;
import com.socialapp.friendships.repository.FriendRequestRepository;
import com.socialapp.friendships.repository.FriendshipRepository;
import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.entity.enums.PrimaryRole;
import com.socialapp.knowledge.repository.UserProfessionalProfileRepository;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link FriendshipService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3 BDD
 * Given/When/Then) — see {@code PostServiceTest} for the full rationale.
 *
 * <p>{@code getSuggestions} sources its candidate pool via {@code
 * friendSuggestionCache.getOrLoad(userId, supplier)}. Since {@link FriendSuggestionCache} is
 * fully mocked, the {@code supplier} lambda that would call {@link Neo4jClient} is captured but
 * never invoked — so the Neo4j query logic never actually runs in these tests, and {@code
 * neo4jClient} needs no stubbing at all (only a plain {@code @Mock} for constructor injection).
 */
@ExtendWith(MockitoExtension.class)
class FriendshipServiceTest {

  private static final Integer ACTOR_ID = 1;
  private static final Integer OTHER_ID = 2;
  private static final Integer THIRD_ID = 3;
  private static final Integer REQUEST_ID = 10;

  @Mock private FriendRequestRepository friendRequestRepository;
  @Mock private FriendshipRepository friendshipRepository;
  @Mock private UserRepository userRepository;
  @Mock private NotificationService notificationService;
  @Mock private UserProfileCache userProfileCache;
  @Mock private FriendSuggestionCache friendSuggestionCache;
  @Mock private UserProfessionalProfileRepository professionalProfileRepository;
  @Mock private Neo4jClient neo4jClient;
  @Mock private BlockQueryService blockQueryService;

  @InjectMocks private FriendshipService friendshipService;

  @Captor private ArgumentCaptor<SendNotificationRequest> notificationCaptor;
  @Captor private ArgumentCaptor<FriendRequestEntity> requestCaptor;

  private static UserEntity user(Integer id, String fullName) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setFullName(fullName);
    return user;
  }

  private static FriendRequestEntity pendingRequest(
      Integer id, Integer requesterId, Integer addresseeId) {
    FriendRequestEntity request = new FriendRequestEntity();
    request.setId(id);
    request.setRequesterId(requesterId);
    request.setAddresseeId(addresseeId);
    request.setStatus(FriendRequestStatus.PENDING);
    return request;
  }

  private static UserProfileDto profile(Integer id) {
    return new UserProfileDto(id, "user" + id, "User " + id, null);
  }

  // =====================================================================
  // sendFriendRequest
  // =====================================================================

  @Nested
  @DisplayName("sendFriendRequest")
  class SendFriendRequestTests {

    @Test
    @DisplayName("should reject sending a request to yourself")
    void shouldThrowValidationException_whenSendingToSelf() {
      // When / Then
      assertThatThrownBy(() -> friendshipService.sendFriendRequest(ACTOR_ID, ACTOR_ID))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("cannot send a friend request to yourself");
    }

    @Test
    @DisplayName("should reject when the addressee does not exist")
    void shouldThrowNotFoundException_whenAddresseeDoesNotExist() {
      // Given
      when(userRepository.existsById(OTHER_ID)).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> friendshipService.sendFriendRequest(ACTOR_ID, OTHER_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should refuse a request across a block, without saying it is a block")
    void shouldThrowValidationException_whenBlocked() {
      // Given: the block may have been placed by either side
      when(userRepository.existsById(OTHER_ID)).thenReturn(true);
      when(blockQueryService.isBlockedEitherWay(ACTOR_ID, OTHER_ID)).thenReturn(true);

      // When / Then — blocking cancels the request in flight, but nothing stopped the blocked user
      // sending a new one, which landed straight back in the blocker's pending list. The message
      // refuses without confirming why.
      assertThatThrownBy(() -> friendshipService.sendFriendRequest(ACTOR_ID, OTHER_ID))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("cannot send a friend request to this user")
          .hasMessageNotContaining("block");
      verify(friendRequestRepository, never()).saveAndFlush(any());
      verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("should check for a block before touching the friendship graph")
    void shouldCheckBlockBeforeGraphLookup() {
      // Given
      when(userRepository.existsById(OTHER_ID)).thenReturn(true);
      when(blockQueryService.isBlockedEitherWay(ACTOR_ID, OTHER_ID)).thenReturn(true);

      // When / Then — a blocked pair is not friends by definition, so the Neo4j round trip is
      // wasted work on a request that is going to be refused either way
      assertThatThrownBy(() -> friendshipService.sendFriendRequest(ACTOR_ID, OTHER_ID))
          .isInstanceOf(ValidationException.class);
      verify(friendshipRepository, never()).areFriends(ACTOR_ID, OTHER_ID);
    }

    @Test
    @DisplayName("should reject when the users are already friends")
    void shouldThrowValidationException_whenAlreadyFriends() {
      // Given
      when(userRepository.existsById(OTHER_ID)).thenReturn(true);
      when(friendshipRepository.areFriends(ACTOR_ID, OTHER_ID)).thenReturn(true);

      // When / Then
      assertThatThrownBy(() -> friendshipService.sendFriendRequest(ACTOR_ID, OTHER_ID))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("already friends");
    }

    @Test
    @DisplayName("should reject when a pending request already exists between the users")
    void shouldThrowValidationException_whenPendingRequestAlreadyExists() {
      // Given
      when(userRepository.existsById(OTHER_ID)).thenReturn(true);
      when(friendshipRepository.areFriends(ACTOR_ID, OTHER_ID)).thenReturn(false);
      when(friendRequestRepository.hasPendingRequestBetween(ACTOR_ID, OTHER_ID)).thenReturn(true);

      // When / Then
      assertThatThrownBy(() -> friendshipService.sendFriendRequest(ACTOR_ID, OTHER_ID))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("pending friend request already exists");
    }

    @Test
    @DisplayName(
        "should reject when a concurrent insert violates the unique pending-pair constraint")
    void shouldThrowValidationException_whenConcurrentInsertViolatesConstraint() {
      // Given
      when(userRepository.existsById(OTHER_ID)).thenReturn(true);
      when(friendshipRepository.areFriends(ACTOR_ID, OTHER_ID)).thenReturn(false);
      when(friendRequestRepository.hasPendingRequestBetween(ACTOR_ID, OTHER_ID)).thenReturn(false);
      doThrow(new DataIntegrityViolationException("duplicate"))
          .when(friendRequestRepository)
          .saveAndFlush(any());

      // When / Then
      assertThatThrownBy(() -> friendshipService.sendFriendRequest(ACTOR_ID, OTHER_ID))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("pending friend request already exists");
      verify(notificationService, never()).send(any());
    }

    @Test
    @DisplayName("should create the request and notify the addressee using the actor's full name")
    void shouldCreateRequestAndNotify_withActorFullName() {
      // Given
      when(userRepository.existsById(OTHER_ID)).thenReturn(true);
      when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.of(user(ACTOR_ID, "Alice")));

      // When
      friendshipService.sendFriendRequest(ACTOR_ID, OTHER_ID);

      // Then
      verify(friendRequestRepository).saveAndFlush(requestCaptor.capture());
      assertThat(requestCaptor.getValue().getRequesterId()).isEqualTo(ACTOR_ID);
      assertThat(requestCaptor.getValue().getAddresseeId()).isEqualTo(OTHER_ID);
      assertThat(requestCaptor.getValue().getStatus()).isEqualTo(FriendRequestStatus.PENDING);
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getBody())
          .isEqualTo("Alice sent you a friend request");
    }

    @Test
    @DisplayName("should fall back to \"Someone\" when the actor's full name is blank")
    void shouldFallBackToSomeone_whenActorNameBlank() {
      // Given
      when(userRepository.existsById(OTHER_ID)).thenReturn(true);
      when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.of(user(ACTOR_ID, "   ")));

      // When
      friendshipService.sendFriendRequest(ACTOR_ID, OTHER_ID);

      // Then
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getBody()).startsWith("Someone sent you");
    }

    @Test
    @DisplayName("should fall back to \"Someone\" when the actor's full name is null")
    void shouldFallBackToSomeone_whenActorNameNull() {
      // Given
      when(userRepository.existsById(OTHER_ID)).thenReturn(true);
      when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.of(user(ACTOR_ID, null)));

      // When
      friendshipService.sendFriendRequest(ACTOR_ID, OTHER_ID);

      // Then
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getBody()).startsWith("Someone sent you");
    }

    @Test
    @DisplayName("should fall back to \"Someone\" when the actor's user record no longer exists")
    void shouldFallBackToSomeone_whenActorNotFound() {
      // Given
      when(userRepository.existsById(OTHER_ID)).thenReturn(true);
      when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.empty());

      // When
      friendshipService.sendFriendRequest(ACTOR_ID, OTHER_ID);

      // Then
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getBody()).startsWith("Someone sent you");
    }
  }

  // =====================================================================
  // cancelFriendRequest
  // =====================================================================

  @Nested
  @DisplayName("cancelFriendRequest")
  class CancelFriendRequestTests {

    @Test
    @DisplayName("should reject when the request does not exist")
    void shouldThrowNotFoundException_whenRequestDoesNotExist() {
      // Given
      when(friendRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> friendshipService.cancelFriendRequest(ACTOR_ID, REQUEST_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should reject when the request is no longer pending")
    void shouldThrowValidationException_whenRequestNotPending() {
      // Given
      FriendRequestEntity request = pendingRequest(REQUEST_ID, ACTOR_ID, OTHER_ID);
      request.setStatus(FriendRequestStatus.ACCEPTED);
      when(friendRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

      // When / Then
      assertThatThrownBy(() -> friendshipService.cancelFriendRequest(ACTOR_ID, REQUEST_ID))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("no longer pending");
    }

    @Test
    @DisplayName("should reject when the actor is not the requester")
    void shouldThrowForbiddenException_whenActorIsNotRequester() {
      // Given
      FriendRequestEntity request = pendingRequest(REQUEST_ID, OTHER_ID, ACTOR_ID);
      when(friendRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

      // When / Then
      assertThatThrownBy(() -> friendshipService.cancelFriendRequest(ACTOR_ID, REQUEST_ID))
          .isInstanceOf(ForbiddenException.class)
          .hasMessageContaining("Only the requester can cancel");
    }

    @Test
    @DisplayName("should cancel the request when the actor is the requester")
    void shouldCancelRequest_whenActorIsRequester() {
      // Given
      FriendRequestEntity request = pendingRequest(REQUEST_ID, ACTOR_ID, OTHER_ID);
      when(friendRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

      // When
      friendshipService.cancelFriendRequest(ACTOR_ID, REQUEST_ID);

      // Then
      assertThat(request.getStatus()).isEqualTo(FriendRequestStatus.CANCELLED);
      verify(friendRequestRepository).save(request);
    }
  }

  // =====================================================================
  // acceptFriendRequest
  // =====================================================================

  @Nested
  @DisplayName("acceptFriendRequest")
  class AcceptFriendRequestTests {

    @Test
    @DisplayName("should reject when the actor is not the addressee")
    void shouldThrowForbiddenException_whenActorIsNotAddressee() {
      // Given
      FriendRequestEntity request = pendingRequest(REQUEST_ID, OTHER_ID, OTHER_ID);
      when(friendRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

      // When / Then
      assertThatThrownBy(() -> friendshipService.acceptFriendRequest(ACTOR_ID, REQUEST_ID))
          .isInstanceOf(ForbiddenException.class)
          .hasMessageContaining("Only the addressee can accept");
    }

    @Test
    @DisplayName("should accept, merge the friendship, evict both caches, and notify the requester")
    void shouldAcceptRequest_mergeFriendship_evictCaches_andNotify() {
      // Given
      FriendRequestEntity request = pendingRequest(REQUEST_ID, OTHER_ID, ACTOR_ID);
      when(friendRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
      when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.of(user(ACTOR_ID, "Bob")));

      // When
      friendshipService.acceptFriendRequest(ACTOR_ID, REQUEST_ID);

      // Then
      assertThat(request.getStatus()).isEqualTo(FriendRequestStatus.ACCEPTED);
      verify(friendRequestRepository).save(request);
      verify(friendshipRepository).mergeUser(OTHER_ID);
      verify(friendshipRepository).mergeUser(ACTOR_ID);
      verify(friendshipRepository).createFriendship(OTHER_ID, ACTOR_ID);
      verify(friendSuggestionCache).evict(OTHER_ID);
      verify(friendSuggestionCache).evict(ACTOR_ID);
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getBody())
          .isEqualTo("Bob accepted your friend request");
      assertThat(notificationCaptor.getValue().getRecipientId()).isEqualTo(OTHER_ID);
    }
  }

  // =====================================================================
  // rejectFriendRequest
  // =====================================================================

  @Nested
  @DisplayName("rejectFriendRequest")
  class RejectFriendRequestTests {

    @Test
    @DisplayName("should reject when the actor is not the addressee")
    void shouldThrowForbiddenException_whenActorIsNotAddressee() {
      // Given
      FriendRequestEntity request = pendingRequest(REQUEST_ID, OTHER_ID, OTHER_ID);
      when(friendRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

      // When / Then
      assertThatThrownBy(() -> friendshipService.rejectFriendRequest(ACTOR_ID, REQUEST_ID))
          .isInstanceOf(ForbiddenException.class)
          .hasMessageContaining("Only the addressee can reject");
    }

    @Test
    @DisplayName("should reject the request when the actor is the addressee")
    void shouldRejectRequest_whenActorIsAddressee() {
      // Given
      FriendRequestEntity request = pendingRequest(REQUEST_ID, OTHER_ID, ACTOR_ID);
      when(friendRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

      // When
      friendshipService.rejectFriendRequest(ACTOR_ID, REQUEST_ID);

      // Then
      assertThat(request.getStatus()).isEqualTo(FriendRequestStatus.REJECTED);
      verify(friendRequestRepository).save(request);
    }
  }

  // =====================================================================
  // getFriends
  // =====================================================================

  @Nested
  @DisplayName("getFriends")
  class GetFriendsTests {

    @Test
    @DisplayName("should return all friends with no next cursor when there are no more pages")
    void shouldReturnFriendsWithoutNextCursor_whenFewerThanLimit() {
      // Given
      when(friendshipRepository.findFriendIdsAfterCursor(ACTOR_ID, null, 3))
          .thenReturn(List.of(2, 3));
      when(userProfileCache.getOrLoadAll(eq(Set.of(2, 3)), any()))
          .thenReturn(Map.of(2, profile(2), 3, profile(3)));
      when(friendshipRepository.countFriends(ACTOR_ID)).thenReturn(2L);

      // When
      FriendListResponseDto result = friendshipService.getFriends(ACTOR_ID, null, 2);

      // Then
      assertThat(result.friends()).hasSize(2);
      assertThat(result.hasMore()).isFalse();
      assertThat(result.nextCursor()).isNull();
      assertThat(result.totalCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("should return a page with a next cursor when there are more results")
    void shouldReturnFriendsWithNextCursor_whenMoreThanLimit() {
      // Given
      when(friendshipRepository.findFriendIdsAfterCursor(ACTOR_ID, null, 3))
          .thenReturn(List.of(2, 3, 4));
      when(userProfileCache.getOrLoadAll(eq(Set.of(2, 3)), any()))
          .thenReturn(Map.of(2, profile(2), 3, profile(3)));
      when(friendshipRepository.countFriends(ACTOR_ID)).thenReturn(10L);

      // When
      FriendListResponseDto result = friendshipService.getFriends(ACTOR_ID, null, 2);

      // Then
      assertThat(result.friends()).hasSize(2);
      assertThat(result.hasMore()).isTrue();
      assertThat(result.nextCursor()).isEqualTo(3);
    }

    @Test
    @DisplayName("should return an empty list when the user has no friends")
    void shouldReturnEmptyFriends_whenUserHasNoFriends() {
      // Given
      when(friendshipRepository.findFriendIdsAfterCursor(ACTOR_ID, null, 3)).thenReturn(List.of());
      when(friendshipRepository.countFriends(ACTOR_ID)).thenReturn(0L);

      // When
      FriendListResponseDto result = friendshipService.getFriends(ACTOR_ID, null, 2);

      // Then
      assertThat(result.friends()).isEmpty();
      verify(userProfileCache, never()).getOrLoadAll(any(), any());
    }

    @Test
    @DisplayName(
        "should load missing profiles from the repository when the cache delegates to the batch loader")
    void shouldLoadProfilesFromRepository_whenCacheDelegatesToBatchLoader() {
      // Given
      when(friendshipRepository.findFriendIdsAfterCursor(ACTOR_ID, null, 2)).thenReturn(List.of(2));
      when(friendshipRepository.countFriends(ACTOR_ID)).thenReturn(1L);
      when(userRepository.findAllById(Set.of(2))).thenReturn(List.of(user(2, "Carol")));
      when(userProfileCache.getOrLoadAll(eq(Set.of(2)), any()))
          .thenAnswer(
              invocation -> {
                java.util.function.Function<Set<Integer>, Map<Integer, UserProfileDto>> loader =
                    invocation.getArgument(1);
                return loader.apply(invocation.getArgument(0));
              });

      // When
      FriendListResponseDto result = friendshipService.getFriends(ACTOR_ID, null, 1);

      // Then
      assertThat(result.friends()).hasSize(1);
      assertThat(result.friends().get(0).fullName()).isEqualTo("Carol");
    }
  }

  // =====================================================================
  // getSuggestions
  // =====================================================================

  @Nested
  @DisplayName("getSuggestions")
  class GetSuggestionsTests {

    @Test
    @DisplayName("should return no suggestions when the candidate pool is empty")
    void shouldReturnEmptySuggestions_whenPoolIsEmpty() {
      // Given
      when(friendSuggestionCache.getOrLoad(eq(ACTOR_ID), any())).thenReturn(List.of());

      // When
      List<FriendSuggestionDto> result = friendshipService.getSuggestions(ACTOR_ID, 10);

      // Then
      assertThat(result).isEmpty();
      verify(professionalProfileRepository, never()).findById(any());
    }

    @Test
    @DisplayName(
        "should keep the mutual-friends ranking when the caller has no professional profile")
    void shouldReturnPoolUnranked_whenCallerHasNoProfessionalProfile() {
      // Given
      List<MutualFriendCountDto> pool =
          List.of(new MutualFriendCountDto(2, 5L), new MutualFriendCountDto(3, 1L));
      when(friendSuggestionCache.getOrLoad(eq(ACTOR_ID), any())).thenReturn(pool);
      when(professionalProfileRepository.findById(ACTOR_ID)).thenReturn(Optional.empty());
      when(userProfileCache.getOrLoadAll(eq(Set.of(2, 3)), any()))
          .thenReturn(Map.of(2, profile(2), 3, profile(3)));

      // When
      List<FriendSuggestionDto> result = friendshipService.getSuggestions(ACTOR_ID, 10);

      // Then
      assertThat(result).extracting(dto -> dto.profile().userId()).containsExactly(2, 3);
    }

    @Test
    @DisplayName("should rank same-primary-role candidates first")
    void shouldRankByPrimaryRoleMatch_whenCallerHasProfile() {
      // Given
      List<MutualFriendCountDto> pool =
          List.of(new MutualFriendCountDto(2, 1L), new MutualFriendCountDto(3, 1L));
      when(friendSuggestionCache.getOrLoad(eq(ACTOR_ID), any())).thenReturn(pool);

      UserProfessionalProfileEntity caller =
          professionalProfile(ACTOR_ID, PrimaryRole.BACKEND, List.of("Java"));
      UserProfessionalProfileEntity sameRoleCandidate =
          professionalProfile(3, PrimaryRole.BACKEND, List.of("Java"));
      UserProfessionalProfileEntity differentRoleCandidate =
          professionalProfile(2, PrimaryRole.FRONTEND, List.of("Java"));
      when(professionalProfileRepository.findById(ACTOR_ID)).thenReturn(Optional.of(caller));
      when(professionalProfileRepository.findAllById(Set.of(2, 3)))
          .thenReturn(List.of(sameRoleCandidate, differentRoleCandidate));
      when(userProfileCache.getOrLoadAll(eq(Set.of(2, 3)), any()))
          .thenReturn(Map.of(2, profile(2), 3, profile(3)));

      // When
      List<FriendSuggestionDto> result = friendshipService.getSuggestions(ACTOR_ID, 10);

      // Then — candidate 3 shares the caller's BACKEND role, so it should rank first even though
      // both have equal mutual-friend counts.
      assertThat(result).extracting(dto -> dto.profile().userId()).containsExactly(3, 2);
    }

    @Test
    @DisplayName(
        "should use tech-stack overlap as a tiebreaker when neither candidate shares the caller's role")
    void shouldRankByTechStackOverlap_whenRolesDiffer() {
      // Given
      List<MutualFriendCountDto> pool =
          List.of(new MutualFriendCountDto(2, 1L), new MutualFriendCountDto(3, 1L));
      when(friendSuggestionCache.getOrLoad(eq(ACTOR_ID), any())).thenReturn(pool);

      UserProfessionalProfileEntity caller =
          professionalProfile(ACTOR_ID, PrimaryRole.BACKEND, List.of("Java", "Kotlin"));
      UserProfessionalProfileEntity highOverlap =
          professionalProfile(3, PrimaryRole.FRONTEND, List.of("java", "kotlin"));
      UserProfessionalProfileEntity lowOverlap =
          professionalProfile(2, PrimaryRole.FRONTEND, List.of("Python"));
      when(professionalProfileRepository.findById(ACTOR_ID)).thenReturn(Optional.of(caller));
      when(professionalProfileRepository.findAllById(Set.of(2, 3)))
          .thenReturn(List.of(highOverlap, lowOverlap));
      when(userProfileCache.getOrLoadAll(eq(Set.of(2, 3)), any()))
          .thenReturn(Map.of(2, profile(2), 3, profile(3)));

      // When
      List<FriendSuggestionDto> result = friendshipService.getSuggestions(ACTOR_ID, 10);

      // Then
      assertThat(result).extracting(dto -> dto.profile().userId()).containsExactly(3, 2);
    }

    @Test
    @DisplayName(
        "should break a same-role tie using tech-stack overlap, treating a missing candidate"
            + " profile or missing candidate tech stack as zero")
    void shouldBreakRoleTieUsingTechStackOverlap_treatingMissingProfileOrStackAsZero() {
      // Given — candidates 2 (no profile at all), 3 (profile but no tech stack), and 4 (matching
      // tech stack) all differ from the caller's role, so they tie on sameRole and the tiebreak
      // falls to techStackOverlap for every pair.
      List<MutualFriendCountDto> pool =
          List.of(
              new MutualFriendCountDto(2, 1L),
              new MutualFriendCountDto(3, 1L),
              new MutualFriendCountDto(4, 1L));
      when(friendSuggestionCache.getOrLoad(eq(ACTOR_ID), any())).thenReturn(pool);

      UserProfessionalProfileEntity caller =
          professionalProfile(ACTOR_ID, PrimaryRole.BACKEND, List.of("Java", "Kotlin"));
      UserProfessionalProfileEntity noStack = professionalProfile(3, PrimaryRole.FRONTEND, null);
      UserProfessionalProfileEntity matchingStack =
          professionalProfile(4, PrimaryRole.FRONTEND, List.of("java"));
      when(professionalProfileRepository.findById(ACTOR_ID)).thenReturn(Optional.of(caller));
      // Candidate 2 has no professional profile row at all.
      when(professionalProfileRepository.findAllById(Set.of(2, 3, 4)))
          .thenReturn(List.of(noStack, matchingStack));
      when(userProfileCache.getOrLoadAll(eq(Set.of(2, 3, 4)), any()))
          .thenReturn(Map.of(2, profile(2), 3, profile(3), 4, profile(4)));

      // When
      List<FriendSuggestionDto> result = friendshipService.getSuggestions(ACTOR_ID, 10);

      // Then — candidate 4 has real overlap (1) and ranks first; 2 and 3 both score 0 and tie
      // down to userId ascending.
      assertThat(result).extracting(dto -> dto.profile().userId()).containsExactly(4, 2, 3);
    }

    @Test
    @DisplayName("should treat a missing caller tech stack as zero overlap for every candidate")
    void shouldTreatMissingCallerTechStack_asZeroOverlap() {
      // Given — both candidates differ from the caller's role, so they tie on sameRole and the
      // tiebreak falls to techStackOverlap, which must short-circuit on the caller's null stack.
      List<MutualFriendCountDto> pool =
          List.of(new MutualFriendCountDto(2, 1L), new MutualFriendCountDto(3, 2L));
      when(friendSuggestionCache.getOrLoad(eq(ACTOR_ID), any())).thenReturn(pool);

      UserProfessionalProfileEntity caller =
          professionalProfile(ACTOR_ID, PrimaryRole.BACKEND, null);
      UserProfessionalProfileEntity candidate2 =
          professionalProfile(2, PrimaryRole.FRONTEND, List.of("Java"));
      UserProfessionalProfileEntity candidate3 =
          professionalProfile(3, PrimaryRole.FRONTEND, List.of("Python"));
      when(professionalProfileRepository.findById(ACTOR_ID)).thenReturn(Optional.of(caller));
      when(professionalProfileRepository.findAllById(Set.of(2, 3)))
          .thenReturn(List.of(candidate2, candidate3));
      when(userProfileCache.getOrLoadAll(eq(Set.of(2, 3)), any()))
          .thenReturn(Map.of(2, profile(2), 3, profile(3)));

      // When
      List<FriendSuggestionDto> result = friendshipService.getSuggestions(ACTOR_ID, 10);

      // Then — overlap ties at zero for both, so mutual-friend count (descending) breaks the tie.
      assertThat(result).extracting(dto -> dto.profile().userId()).containsExactly(3, 2);
    }

    @Test
    @DisplayName(
        "should treat a caller with no primary role set as not matching any candidate's role")
    void shouldTreatMissingCallerPrimaryRole_asNoRoleMatch() {
      // Given
      List<MutualFriendCountDto> pool =
          List.of(new MutualFriendCountDto(2, 1L), new MutualFriendCountDto(3, 1L));
      when(friendSuggestionCache.getOrLoad(eq(ACTOR_ID), any())).thenReturn(pool);

      UserProfessionalProfileEntity caller = professionalProfile(ACTOR_ID, null, List.of("Java"));
      UserProfessionalProfileEntity matchingStack =
          professionalProfile(2, PrimaryRole.BACKEND, List.of("java"));
      UserProfessionalProfileEntity noOverlap =
          professionalProfile(3, PrimaryRole.FRONTEND, List.of("Python"));
      when(professionalProfileRepository.findById(ACTOR_ID)).thenReturn(Optional.of(caller));
      when(professionalProfileRepository.findAllById(Set.of(2, 3)))
          .thenReturn(List.of(matchingStack, noOverlap));
      when(userProfileCache.getOrLoadAll(eq(Set.of(2, 3)), any()))
          .thenReturn(Map.of(2, profile(2), 3, profile(3)));

      // When
      List<FriendSuggestionDto> result = friendshipService.getSuggestions(ACTOR_ID, 10);

      // Then — with no primary role, nobody "matches", so it falls straight to tech-stack
      // overlap: candidate 2 shares "java" and ranks first.
      assertThat(result).extracting(dto -> dto.profile().userId()).containsExactly(2, 3);
    }

    @Test
    @DisplayName("should filter out suggestions whose profile could not be loaded")
    void shouldFilterOutSuggestionsWithoutProfile() {
      // Given
      List<MutualFriendCountDto> pool =
          List.of(new MutualFriendCountDto(2, 5L), new MutualFriendCountDto(3, 1L));
      when(friendSuggestionCache.getOrLoad(eq(ACTOR_ID), any())).thenReturn(pool);
      when(professionalProfileRepository.findById(ACTOR_ID)).thenReturn(Optional.empty());
      // User 3's profile could not be found (e.g. deleted account).
      when(userProfileCache.getOrLoadAll(eq(Set.of(2, 3)), any()))
          .thenReturn(Map.of(2, profile(2)));

      // When
      List<FriendSuggestionDto> result = friendshipService.getSuggestions(ACTOR_ID, 10);

      // Then
      assertThat(result).hasSize(1);
      assertThat(result.get(0).profile().userId()).isEqualTo(2);
    }

    @Test
    @DisplayName("should limit the number of suggestions to the requested count")
    void shouldLimitSuggestionsToRequestedCount() {
      // Given
      List<MutualFriendCountDto> pool =
          List.of(
              new MutualFriendCountDto(2, 5L),
              new MutualFriendCountDto(3, 4L),
              new MutualFriendCountDto(4, 3L));
      when(friendSuggestionCache.getOrLoad(eq(ACTOR_ID), any())).thenReturn(pool);
      when(professionalProfileRepository.findById(ACTOR_ID)).thenReturn(Optional.empty());
      when(userProfileCache.getOrLoadAll(eq(Set.of(2)), any())).thenReturn(Map.of(2, profile(2)));

      // When
      List<FriendSuggestionDto> result = friendshipService.getSuggestions(ACTOR_ID, 1);

      // Then
      assertThat(result).hasSize(1);
      verify(userProfileCache).getOrLoadAll(eq(Set.of(2)), any());
    }

    private UserProfessionalProfileEntity professionalProfile(
        Integer userId, PrimaryRole role, List<String> techStack) {
      UserProfessionalProfileEntity profile = new UserProfessionalProfileEntity();
      profile.setUserId(userId);
      profile.setPrimaryRole(role);
      profile.setKnownTechStack(techStack);
      return profile;
    }
  }

  @Nested
  @DisplayName("unfriend")
  class UnfriendTests {

    @Test
    @DisplayName("should delete the friendship from BOTH Neo4j and Postgres")
    void shouldDeleteFromBothStores() {
      // Given / When
      friendshipService.unfriend(1, 2);

      // Then — clearing one store and not the other leaves the pair friends according to
      // whichever one was missed: Neo4j is what getFriends and fan-out read, the accepted row is
      // the record that produced it.
      verify(friendshipRepository).deleteFriendship(1, 2);
      verify(friendRequestRepository).deleteAcceptedBetween(1, 2);
    }

    @Test
    @DisplayName("should evict the suggestion cache of both users")
    void shouldEvictBothSuggestionCaches() {
      // Given / When
      friendshipService.unfriend(1, 2);

      // Then — each is a candidate for the other again, and both mutual-friend counts changed
      verify(friendSuggestionCache).evict(1);
      verify(friendSuggestionCache).evict(2);
    }

    @Test
    @DisplayName("should be idempotent when the two are not friends")
    void shouldBeIdempotent() {
      // Given: nothing to delete — the repositories simply match no rows
      // When / Then: no exception, because the caller asked for a state that is already true
      assertThatCode(() -> friendshipService.unfriend(1, 2)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should reject unfriending yourself")
    void shouldRejectSelfUnfriend() {
      // When / Then
      assertThatThrownBy(() -> friendshipService.unfriend(1, 1))
          .isInstanceOf(ValidationException.class);
      verify(friendshipRepository, never()).deleteFriendship(1, 1);
    }
  }

  @Nested
  @DisplayName("areFriends")
  class AreFriendsTests {

    @Test
    @DisplayName("should answer from a single graph lookup")
    void shouldDelegateToRepository() {
      // Given
      when(friendshipRepository.areFriends(1, 2)).thenReturn(true);

      // When / Then
      assertThat(friendshipService.areFriends(1, 2)).isTrue();
    }
  }

  @Nested
  @DisplayName("getSuggestions — block filtering")
  class GetSuggestionsBlockFilteringTests {

    @Test
    @DisplayName("should drop blocked users from the suggestion pool")
    void shouldDropBlockedCandidates() {
      // Given: two candidates, one of whom is on the caller's block set (either direction)
      when(friendSuggestionCache.getOrLoad(eq(1), any()))
          .thenReturn(List.of(new MutualFriendCountDto(2, 5L), new MutualFriendCountDto(3, 4L)));
      when(blockQueryService.blockedPairIds(1)).thenReturn(java.util.Set.of(2));
      when(professionalProfileRepository.findById(1)).thenReturn(Optional.empty());
      when(userProfileCache.getOrLoadAll(eq(java.util.Set.of(3)), any()))
          .thenReturn(Map.of(3, new UserProfileDto(3, "u3", "Three", null)));

      // When
      List<FriendSuggestionDto> suggestions = friendshipService.getSuggestions(1, 10);

      // Then — suggesting someone who blocked you is a particularly bad way to find out
      assertThat(suggestions).extracting(s -> s.profile().userId()).containsExactly(3);
    }
  }

  @Nested
  @DisplayName("getPendingRequests — block filtering")
  class GetPendingRequestsBlockFilteringTests {

    @Test
    @DisplayName("should hide a pending request sent by someone in the caller's block set")
    void shouldHideRequestFromBlockedUser() {
      // Given: a row written before sendFriendRequest learned to refuse these
      FriendRequestEntity fromBlocked = new FriendRequestEntity();
      fromBlocked.setId(1);
      fromBlocked.setRequesterId(OTHER_ID);
      fromBlocked.setAddresseeId(ACTOR_ID);
      fromBlocked.setStatus(FriendRequestStatus.PENDING);

      FriendRequestEntity fromStranger = new FriendRequestEntity();
      fromStranger.setId(2);
      fromStranger.setRequesterId(THIRD_ID);
      fromStranger.setAddresseeId(ACTOR_ID);
      fromStranger.setStatus(FriendRequestStatus.PENDING);

      when(friendRequestRepository.findByAddresseeIdAndStatusOrderByCreatedAtDesc(
              ACTOR_ID, FriendRequestStatus.PENDING))
          .thenReturn(List.of(fromBlocked, fromStranger));
      when(blockQueryService.blockedPairIds(ACTOR_ID)).thenReturn(Set.of(OTHER_ID));
      when(userProfileCache.getOrLoadAll(eq(Set.of(THIRD_ID)), any()))
          .thenReturn(Map.of(THIRD_ID, new UserProfileDto(THIRD_ID, "u3", "Three", null)));

      // When
      List<PendingFriendRequestDto> pending = friendshipService.getPendingRequests(ACTOR_ID);

      // Then — this screen is where a leftover row would put a blocked user's name back in front
      // of the person who blocked them
      assertThat(pending)
          .extracting(PendingFriendRequestDto::requesterId)
          .containsExactly(THIRD_ID);
    }
  }
}
