package com.socialapp.posts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.moderation.exception.UserBannedException;
import com.socialapp.moderation.service.UserBanService;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.posts.dto.UpsertPostReactionRequestDto;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.PostReactionEntity;
import com.socialapp.posts.entity.PostReactionId;
import com.socialapp.posts.entity.enums.ReactionType;
import com.socialapp.posts.repository.PostReactionRepository;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.reputation.entity.enums.RepSourceType;
import com.socialapp.reputation.event.ReputationEventPublisher;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link PostReactionService}, per ISTQB CTFL v4.0.1 Section 2.2.1
 * (component testing) — every collaborator is mocked with Mockito so the service is tested in
 * isolation, without a Spring context. BDD Given/When/Then per Section 2.1.3.
 */
@ExtendWith(MockitoExtension.class)
class PostReactionServiceTest {

  private static final Integer USER_ID = 1;
  private static final Integer POST_ID = 100;

  @Mock private PostReactionRepository postReactionRepository;
  @Mock private PostRepository postRepository;
  @Mock private UserBanService userBanService;
  @Mock private UserRepository userRepository;
  @Mock private NotificationService notificationService;
  @Mock private ReputationEventPublisher reputationEventPublisher;

  @InjectMocks private PostReactionService postReactionService;

  @Captor private ArgumentCaptor<PostReactionEntity> reactionCaptor;
  @Captor private ArgumentCaptor<SendNotificationRequest> notificationCaptor;

  private static PostEntity samplePost(Integer authorId) {
    PostEntity post = new PostEntity();
    post.setId(POST_ID);
    post.setAuthorId(authorId);
    return post;
  }

  private static UpsertPostReactionRequestDto sampleRequest(ReactionType type) {
    UpsertPostReactionRequestDto request = new UpsertPostReactionRequestDto();
    request.setReactionType(type);
    return request;
  }

  // =====================================================================
  // upsertReaction
  // =====================================================================

  @Nested
  @DisplayName("upsertReaction")
  class UpsertReactionTests {

    @Test
    @DisplayName("should save a new reaction and notify the post author")
    void shouldSaveNewReactionAndNotifyAuthor_whenReactorIsNotTheAuthor() {
      // Given
      PostReactionId id = new PostReactionId(USER_ID, POST_ID);
      when(userBanService.isUserBanned(USER_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(2)));
      when(postReactionRepository.existsById(id)).thenReturn(false);
      when(postReactionRepository.findById(id)).thenReturn(Optional.empty());
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(sampleUser("Alice")));

      // When
      postReactionService.upsertReaction(USER_ID, POST_ID, sampleRequest(ReactionType.LIKE));

      // Then
      verify(postReactionRepository).save(reactionCaptor.capture());
      assertThat(reactionCaptor.getValue().getReactionType()).isEqualTo(ReactionType.LIKE);

      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getRecipientId()).isEqualTo(2);
      assertThat(notificationCaptor.getValue().getBody()).contains("Alice");

      verify(reputationEventPublisher)
          .award(2, RepSourceType.REACTION_RECEIVED, POST_ID + ":" + USER_ID);
    }

    @Test
    @DisplayName("should update the reaction type without re-notifying when it already exists")
    void shouldUpdateReactionWithoutNotifying_whenReactionAlreadyExists() {
      // Given
      PostReactionId id = new PostReactionId(USER_ID, POST_ID);
      PostReactionEntity existing = new PostReactionEntity(id, ReactionType.LIKE, null);
      when(userBanService.isUserBanned(USER_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(2)));
      when(postReactionRepository.existsById(id)).thenReturn(true);
      when(postReactionRepository.findById(id)).thenReturn(Optional.of(existing));

      // When
      postReactionService.upsertReaction(USER_ID, POST_ID, sampleRequest(ReactionType.LOVE));

      // Then
      verify(postReactionRepository).save(reactionCaptor.capture());
      assertThat(reactionCaptor.getValue().getReactionType()).isEqualTo(ReactionType.LOVE);
      verifyNoInteractions(notificationService);
      verifyNoInteractions(reputationEventPublisher);
    }

    @Test
    @DisplayName("should not notify anyone when the author reacts to their own post")
    void shouldNotNotify_whenAuthorReactsToOwnPost() {
      // Given
      PostReactionId id = new PostReactionId(USER_ID, POST_ID);
      when(userBanService.isUserBanned(USER_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(USER_ID)));
      when(postReactionRepository.existsById(id)).thenReturn(false);
      when(postReactionRepository.findById(id)).thenReturn(Optional.empty());

      // When
      postReactionService.upsertReaction(USER_ID, POST_ID, sampleRequest(ReactionType.LIKE));

      // Then
      verify(postReactionRepository).save(any());
      verifyNoInteractions(notificationService);
      verifyNoInteractions(reputationEventPublisher);
    }

    @Test
    @DisplayName("should throw NotFoundException when the post does not exist")
    void shouldThrowNotFoundException_whenPostDoesNotExist() {
      // Given
      when(userBanService.isUserBanned(USER_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(
              () ->
                  postReactionService.upsertReaction(
                      USER_ID, POST_ID, sampleRequest(ReactionType.LIKE)))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Post not found");
      verifyNoInteractions(postReactionRepository, reputationEventPublisher);
    }

    @Test
    @DisplayName("should throw UserBannedException when the reactor is banned")
    void shouldThrowUserBannedException_whenReactorIsBanned() {
      // Given
      OffsetDateTime expiry = OffsetDateTime.now().plusDays(1);
      when(userBanService.isUserBanned(USER_ID)).thenReturn(true);
      when(userBanService.getBanExpiry(USER_ID)).thenReturn(expiry);

      // When / Then
      assertThatThrownBy(
              () ->
                  postReactionService.upsertReaction(
                      USER_ID, POST_ID, sampleRequest(ReactionType.LIKE)))
          .isInstanceOf(UserBannedException.class);
      verifyNoInteractions(
          postRepository, postReactionRepository, notificationService, reputationEventPublisher);
    }

    @Test
    @DisplayName("should fall back to a generic name when the reactor's full name is blank")
    void shouldFallBackToGenericName_whenReactorFullNameIsBlank() {
      // Given
      PostReactionId id = new PostReactionId(USER_ID, POST_ID);
      when(userBanService.isUserBanned(USER_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(2)));
      when(postReactionRepository.existsById(id)).thenReturn(false);
      when(postReactionRepository.findById(id)).thenReturn(Optional.empty());
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(sampleUser(" ")));

      // When
      postReactionService.upsertReaction(USER_ID, POST_ID, sampleRequest(ReactionType.LIKE));

      // Then
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getBody()).contains("Someone");
    }

    @Test
    @DisplayName("should fall back to a generic name when the reactor is not found")
    void shouldFallBackToGenericName_whenReactorIsNotFound() {
      // Given
      PostReactionId id = new PostReactionId(USER_ID, POST_ID);
      when(userBanService.isUserBanned(USER_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(2)));
      when(postReactionRepository.existsById(id)).thenReturn(false);
      when(postReactionRepository.findById(id)).thenReturn(Optional.empty());
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      // When
      postReactionService.upsertReaction(USER_ID, POST_ID, sampleRequest(ReactionType.LIKE));

      // Then
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getBody()).contains("Someone");
    }
  }

  // =====================================================================
  // removeReaction
  // =====================================================================

  @Nested
  @DisplayName("removeReaction")
  class RemoveReactionTests {

    @Test
    @DisplayName("should delete the reaction and revoke reputation when it exists")
    void shouldDeleteReactionAndRevokeRep_whenItExists() {
      // Given
      PostReactionId id = new PostReactionId(USER_ID, POST_ID);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(2)));
      when(postReactionRepository.existsById(id)).thenReturn(true);

      // When
      postReactionService.removeReaction(USER_ID, POST_ID);

      // Then
      verify(postReactionRepository).deleteById(id);
      verify(reputationEventPublisher)
          .revoke(2, RepSourceType.REACTION_RECEIVED, POST_ID + ":" + USER_ID);
    }

    @Test
    @DisplayName(
        "should not revoke reputation when the author removes a reaction on their own post")
    void shouldNotRevokeRep_whenAuthorRemovesReactionOnOwnPost() {
      // Given
      PostReactionId id = new PostReactionId(USER_ID, POST_ID);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(USER_ID)));
      when(postReactionRepository.existsById(id)).thenReturn(true);

      // When
      postReactionService.removeReaction(USER_ID, POST_ID);

      // Then
      verify(postReactionRepository).deleteById(id);
      verifyNoInteractions(reputationEventPublisher);
    }

    @Test
    @DisplayName("should throw NotFoundException when the reaction does not exist")
    void shouldThrowNotFoundException_whenReactionDoesNotExist() {
      // Given
      PostReactionId id = new PostReactionId(USER_ID, POST_ID);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(2)));
      when(postReactionRepository.existsById(id)).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> postReactionService.removeReaction(USER_ID, POST_ID))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Reaction not found");
      verify(postReactionRepository, never()).deleteById(any());
      verifyNoInteractions(reputationEventPublisher);
    }

    @Test
    @DisplayName("should throw NotFoundException when the post does not exist")
    void shouldThrowNotFoundException_whenPostDoesNotExist() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> postReactionService.removeReaction(USER_ID, POST_ID))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Post not found");
      verifyNoInteractions(postReactionRepository, reputationEventPublisher);
    }
  }

  private static UserEntity sampleUser(String fullName) {
    UserEntity user = new UserEntity();
    user.setId(USER_ID);
    user.setFullName(fullName);
    return user;
  }
}
