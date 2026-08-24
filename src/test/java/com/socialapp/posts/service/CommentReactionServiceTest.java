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
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.posts.dto.UpsertPostReactionRequestDto;
import com.socialapp.posts.entity.CommentEntity;
import com.socialapp.posts.entity.CommentReactionEntity;
import com.socialapp.posts.entity.CommentReactionId;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.ReactionType;
import com.socialapp.posts.repository.CommentReactionRepository;
import com.socialapp.posts.repository.CommentRepository;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link CommentReactionService}, per ISTQB CTFL v4.0.1 Section 2.2.1
 * (component testing) — every collaborator is mocked with Mockito so the service is tested in
 * isolation, without a Spring context. BDD Given/When/Then per Section 2.1.3.
 */
@ExtendWith(MockitoExtension.class)
class CommentReactionServiceTest {

  private static final Integer USER_ID = 1;
  private static final Integer POST_ID = 100;
  private static final Integer COMMENT_ID = 500;
  private static final Integer COMMENT_AUTHOR_ID = 7;

  @Mock private CommentReactionRepository commentReactionRepository;
  @Mock private CommentRepository commentRepository;
  @Mock private PostRepository postRepository;
  @Mock private PostVisibilityService postVisibilityService;
  @Mock private UserBanService userBanService;
  @Mock private NotificationService notificationService;
  @Mock private UserRepository userRepository;

  @InjectMocks private CommentReactionService commentReactionService;

  @Captor private ArgumentCaptor<CommentReactionEntity> reactionCaptor;
  @Captor private ArgumentCaptor<SendNotificationRequest> notificationCaptor;

  private static PostEntity samplePost() {
    PostEntity post = new PostEntity();
    post.setId(POST_ID);
    post.setAuthorId(42);
    return post;
  }

  private static CommentEntity sampleComment(Integer authorId, Integer postId) {
    CommentEntity comment = new CommentEntity();
    comment.setId(COMMENT_ID);
    comment.setPostId(postId);
    comment.setAuthorId(authorId);
    return comment;
  }

  private static UserEntity sampleUser(String fullName) {
    UserEntity user = new UserEntity();
    user.setId(USER_ID);
    user.setFullName(fullName);
    return user;
  }

  private static UpsertPostReactionRequestDto sampleRequest(ReactionType type) {
    UpsertPostReactionRequestDto request = new UpsertPostReactionRequestDto();
    request.setReactionType(type);
    return request;
  }

  /** The happy-path stubbing every "post and comment are readable" case needs. */
  private void givenReadableComment(Integer commentAuthorId) {
    when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost()));
    when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
    when(commentRepository.findById(COMMENT_ID))
        .thenReturn(Optional.of(sampleComment(commentAuthorId, POST_ID)));
  }

  // =====================================================================
  // upsertReaction
  // =====================================================================

  @Nested
  @DisplayName("upsertReaction")
  class UpsertReactionTests {

    @Test
    @DisplayName("should save a new reaction and notify the comment author")
    void shouldSaveAndNotify_whenReactorIsNotTheCommentAuthor() {
      // Given — a reader reacting to somebody else's comment
      CommentReactionId id = new CommentReactionId(USER_ID, COMMENT_ID);
      when(userBanService.isUserBanned(USER_ID)).thenReturn(false);
      givenReadableComment(COMMENT_AUTHOR_ID);
      when(commentReactionRepository.existsById(id)).thenReturn(false);
      when(commentReactionRepository.findById(id)).thenReturn(Optional.empty());
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(sampleUser("Alice")));

      // When
      commentReactionService.upsertReaction(
          USER_ID, POST_ID, COMMENT_ID, sampleRequest(ReactionType.INSIGHT));

      // Then — the row carries the chosen type
      verify(commentReactionRepository).save(reactionCaptor.capture());
      assertThat(reactionCaptor.getValue().getReactionType()).isEqualTo(ReactionType.INSIGHT);
      assertThat(reactionCaptor.getValue().getId()).isEqualTo(id);

      // Then — and the notification points at the COMMENT, not the post: the client has to open
      // the thread at the reply that was reacted to
      verify(notificationService).send(notificationCaptor.capture());
      SendNotificationRequest sent = notificationCaptor.getValue();
      assertThat(sent.getRecipientId()).isEqualTo(COMMENT_AUTHOR_ID);
      assertThat(sent.getType()).isEqualTo(NotificationType.COMMENT_LIKED);
      assertThat(sent.getReferenceId()).isEqualTo(COMMENT_ID);
      assertThat(sent.getReferenceType()).isEqualTo("COMMENT");
      assertThat(sent.getBody()).contains("Alice");
    }

    @Test
    @DisplayName("should fall back to a generic actor name when the reactor has no full name")
    void shouldUseGenericName_whenReactorHasNoFullName() {
      // Given — a user row with a blank name still has to produce readable notification text
      CommentReactionId id = new CommentReactionId(USER_ID, COMMENT_ID);
      when(userBanService.isUserBanned(USER_ID)).thenReturn(false);
      givenReadableComment(COMMENT_AUTHOR_ID);
      when(commentReactionRepository.existsById(id)).thenReturn(false);
      when(commentReactionRepository.findById(id)).thenReturn(Optional.empty());
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(sampleUser("   ")));

      // When
      commentReactionService.upsertReaction(
          USER_ID, POST_ID, COMMENT_ID, sampleRequest(ReactionType.LIKE));

      // Then
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getBody()).startsWith("Someone");
    }

    @Test
    @DisplayName("should change the reaction type without notifying again")
    void shouldNotNotifyTwice_whenReactionAlreadyExists() {
      // Given — swapping LIKE for INSIGHT is the same single act of engagement; notifying again
      // would let one reader ring somebody's bell as often as they liked
      CommentReactionId id = new CommentReactionId(USER_ID, COMMENT_ID);
      CommentReactionEntity existing =
          new CommentReactionEntity(id, ReactionType.LIKE, OffsetDateTime.now());
      when(userBanService.isUserBanned(USER_ID)).thenReturn(false);
      givenReadableComment(COMMENT_AUTHOR_ID);
      when(commentReactionRepository.existsById(id)).thenReturn(true);
      when(commentReactionRepository.findById(id)).thenReturn(Optional.of(existing));

      // When
      commentReactionService.upsertReaction(
          USER_ID, POST_ID, COMMENT_ID, sampleRequest(ReactionType.INSIGHT));

      // Then
      verify(commentReactionRepository).save(reactionCaptor.capture());
      assertThat(reactionCaptor.getValue().getReactionType()).isEqualTo(ReactionType.INSIGHT);
      verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("should not notify when reacting to one's own comment")
    void shouldNotNotify_whenReactingToOwnComment() {
      // Given
      CommentReactionId id = new CommentReactionId(USER_ID, COMMENT_ID);
      when(userBanService.isUserBanned(USER_ID)).thenReturn(false);
      givenReadableComment(USER_ID);
      when(commentReactionRepository.existsById(id)).thenReturn(false);
      when(commentReactionRepository.findById(id)).thenReturn(Optional.empty());

      // When
      commentReactionService.upsertReaction(
          USER_ID, POST_ID, COMMENT_ID, sampleRequest(ReactionType.CLAP));

      // Then
      verify(commentReactionRepository).save(any());
      verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("should reject a banned user before touching anything else")
    void shouldThrowUserBanned_whenUserIsBanned() {
      // Given
      OffsetDateTime expiry = OffsetDateTime.now().plusDays(3);
      when(userBanService.isUserBanned(USER_ID)).thenReturn(true);
      when(userBanService.getBanExpiry(USER_ID)).thenReturn(expiry);

      // When / Then
      assertThatThrownBy(
              () ->
                  commentReactionService.upsertReaction(
                      USER_ID, POST_ID, COMMENT_ID, sampleRequest(ReactionType.LIKE)))
          .isInstanceOf(UserBannedException.class);

      verifyNoInteractions(postRepository, commentRepository, commentReactionRepository);
    }

    @Test
    @DisplayName("should report a post the caller may not read as missing")
    void shouldThrowNotFound_whenPostIsNotVisible() {
      // Given — reacting to a comment on a PRIVATE post would confirm the post exists and fire a
      // notification at an author who never shared it
      when(userBanService.isUserBanned(USER_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost()));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(false);

      // When / Then
      assertThatThrownBy(
              () ->
                  commentReactionService.upsertReaction(
                      USER_ID, POST_ID, COMMENT_ID, sampleRequest(ReactionType.LIKE)))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Post not found");

      verify(commentReactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("should report a missing post as missing")
    void shouldThrowNotFound_whenPostDoesNotExist() {
      // Given
      when(userBanService.isUserBanned(USER_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(
              () ->
                  commentReactionService.upsertReaction(
                      USER_ID, POST_ID, COMMENT_ID, sampleRequest(ReactionType.LIKE)))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Post not found");
    }

    @Test
    @DisplayName("should report a missing comment as missing")
    void shouldThrowNotFound_whenCommentDoesNotExist() {
      // Given
      when(userBanService.isUserBanned(USER_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost()));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(
              () ->
                  commentReactionService.upsertReaction(
                      USER_ID, POST_ID, COMMENT_ID, sampleRequest(ReactionType.LIKE)))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Comment not found");
    }

    @Test
    @DisplayName("should refuse a comment that belongs to a different post")
    void shouldThrowNotFound_whenCommentBelongsToAnotherPost() {
      // Given — otherwise /posts/{a readable post}/comments/{id of a comment on a hidden post}
      // would reach a comment the caller may not see, by borrowing the path of one they can
      when(userBanService.isUserBanned(USER_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost()));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      when(commentRepository.findById(COMMENT_ID))
          .thenReturn(Optional.of(sampleComment(COMMENT_AUTHOR_ID, 999)));

      // When / Then
      assertThatThrownBy(
              () ->
                  commentReactionService.upsertReaction(
                      USER_ID, POST_ID, COMMENT_ID, sampleRequest(ReactionType.LIKE)))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Comment not found");

      verify(commentReactionRepository, never()).save(any());
    }
  }

  // =====================================================================
  // removeReaction
  // =====================================================================

  @Nested
  @DisplayName("removeReaction")
  class RemoveReactionTests {

    @Test
    @DisplayName("should delete an existing reaction")
    void shouldDelete_whenReactionExists() {
      // Given
      CommentReactionId id = new CommentReactionId(USER_ID, COMMENT_ID);
      givenReadableComment(COMMENT_AUTHOR_ID);
      when(commentReactionRepository.existsById(id)).thenReturn(true);

      // When
      commentReactionService.removeReaction(USER_ID, POST_ID, COMMENT_ID);

      // Then
      verify(commentReactionRepository).deleteById(id);
    }

    @Test
    @DisplayName("should report a reaction that was never there as missing")
    void shouldThrowNotFound_whenReactionDoesNotExist() {
      // Given
      CommentReactionId id = new CommentReactionId(USER_ID, COMMENT_ID);
      givenReadableComment(COMMENT_AUTHOR_ID);
      when(commentReactionRepository.existsById(id)).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> commentReactionService.removeReaction(USER_ID, POST_ID, COMMENT_ID))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Reaction not found");

      verify(commentReactionRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("should not check the ban list — removing a reaction is always allowed")
    void shouldNotCheckBanStatus_whenRemoving() {
      // Given — a banned user must still be able to take back something they posted before the
      // ban; the ban stops new contributions, not retractions
      CommentReactionId id = new CommentReactionId(USER_ID, COMMENT_ID);
      givenReadableComment(COMMENT_AUTHOR_ID);
      when(commentReactionRepository.existsById(id)).thenReturn(true);

      // When
      commentReactionService.removeReaction(USER_ID, POST_ID, COMMENT_ID);

      // Then
      verifyNoInteractions(userBanService);
    }
  }
}
