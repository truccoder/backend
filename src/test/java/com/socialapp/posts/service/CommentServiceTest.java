package com.socialapp.posts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.moderation.exception.UserBannedException;
import com.socialapp.moderation.service.UserBanService;
import com.socialapp.newsfeed.service.NewsfeedService;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.posts.dto.CreateCommentRequestDto;
import com.socialapp.posts.dto.UpdateCommentRequestDto;
import com.socialapp.posts.entity.CommentEntity;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.repository.CommentRepository;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link CommentService}, per ISTQB CTFL v4.0.1 Section 2.2.1
 * (component testing) — every collaborator is mocked with Mockito so the service is tested in
 * isolation, without a Spring context. BDD Given/When/Then per Section 2.1.3.
 */
@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

  private static final Integer AUTHOR_ID = 1;
  private static final Integer POST_ID = 100;
  private static final Integer COMMENT_ID = 10;

  @Mock private CommentRepository commentRepository;
  @Mock private PostRepository postRepository;
  @Mock private UserBanService userBanService;
  @Mock private UserRepository userRepository;
  @Mock private NotificationService notificationService;
  @Mock private NewsfeedService newsfeedService;

  @InjectMocks private CommentService commentService;

  @Captor private ArgumentCaptor<CommentEntity> commentCaptor;
  @Captor private ArgumentCaptor<SendNotificationRequest> notificationCaptor;

  private static PostEntity samplePost(Integer authorId) {
    PostEntity post = new PostEntity();
    post.setId(POST_ID);
    post.setAuthorId(authorId);
    return post;
  }

  private static CommentEntity sampleComment(Integer authorId, Integer parentId) {
    CommentEntity comment = new CommentEntity();
    comment.setId(COMMENT_ID);
    comment.setPostId(POST_ID);
    comment.setAuthorId(authorId);
    comment.setContent("Original content");
    comment.setParentId(parentId);
    return comment;
  }

  // =====================================================================
  // createComment
  // =====================================================================

  @Nested
  @DisplayName("createComment")
  class CreateCommentTests {

    @Test
    @DisplayName("should push the new comment total into the feed cache")
    void shouldRefreshCachedCommentCount_whenCommentIsCreated() {
      // Given — the feed never falls back to Postgres, so posting a comment used to leave the
      // card reading "0 comments" no matter how many times it was refetched (B7)
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(2)));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(sampleUser("Alice")));
      when(commentRepository.countByPostId(POST_ID)).thenReturn(6L);
      CreateCommentRequestDto request = new CreateCommentRequestDto();
      request.setContent("Nice post!");

      // When
      commentService.createComment(AUTHOR_ID, POST_ID, request);

      // Then
      verify(newsfeedService).updateCachedCommentCount(POST_ID, 6);
    }

    @Test
    @DisplayName("should save a top-level comment and notify the post author")
    void shouldSaveCommentAndNotifyAuthor_whenCommenterIsNotTheAuthor() {
      // Given
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(2)));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(sampleUser("Alice")));
      CreateCommentRequestDto request = new CreateCommentRequestDto();
      request.setContent("Nice post!");

      // When
      commentService.createComment(AUTHOR_ID, POST_ID, request);

      // Then
      verify(commentRepository).save(commentCaptor.capture());
      assertThat(commentCaptor.getValue().getContent()).isEqualTo("Nice post!");
      assertThat(commentCaptor.getValue().getParentId()).isNull();

      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getRecipientId()).isEqualTo(2);
      assertThat(notificationCaptor.getValue().getBody()).contains("Alice");
    }

    @Test
    @DisplayName("should not notify anyone when the author comments on their own post")
    void shouldNotNotify_whenAuthorCommentsOnOwnPost() {
      // Given
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(AUTHOR_ID)));
      CreateCommentRequestDto request = new CreateCommentRequestDto();
      request.setContent("My own comment");

      // When
      commentService.createComment(AUTHOR_ID, POST_ID, request);

      // Then
      verify(commentRepository).save(any());
      verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("should save a valid reply to a top-level comment")
    void shouldSaveReply_whenParentIsTopLevelComment() {
      // Given
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(AUTHOR_ID)));
      when(commentRepository.findById(COMMENT_ID))
          .thenReturn(Optional.of(sampleComment(AUTHOR_ID, null)));
      CreateCommentRequestDto request = new CreateCommentRequestDto();
      request.setContent("A reply");
      request.setParentId(COMMENT_ID);

      // When
      commentService.createComment(AUTHOR_ID, POST_ID, request);

      // Then
      verify(commentRepository, times(1)).save(commentCaptor.capture());
      assertThat(commentCaptor.getValue().getParentId()).isEqualTo(COMMENT_ID);
    }

    @Test
    @DisplayName("should throw ValidationException when replying to a reply (nested reply)")
    void shouldThrowValidationException_whenParentIsItselfAReply() {
      // Given
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(AUTHOR_ID)));
      when(commentRepository.findById(COMMENT_ID))
          .thenReturn(Optional.of(sampleComment(AUTHOR_ID, 999)));
      CreateCommentRequestDto request = new CreateCommentRequestDto();
      request.setContent("A nested reply");
      request.setParentId(COMMENT_ID);

      // When / Then
      assertThatThrownBy(() -> commentService.createComment(AUTHOR_ID, POST_ID, request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("top-level comments");
      verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("should throw NotFoundException when the parent comment belongs to another post")
    void shouldThrowNotFoundException_whenParentCommentBelongsToAnotherPost() {
      // Given
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(AUTHOR_ID)));
      CommentEntity parentFromOtherPost = sampleComment(AUTHOR_ID, null);
      parentFromOtherPost.setPostId(999);
      when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(parentFromOtherPost));
      CreateCommentRequestDto request = new CreateCommentRequestDto();
      request.setContent("A reply");
      request.setParentId(COMMENT_ID);

      // When / Then
      assertThatThrownBy(() -> commentService.createComment(AUTHOR_ID, POST_ID, request))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should throw ValidationException when content is blank")
    void shouldThrowValidationException_whenContentIsBlank() {
      // Given
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      CreateCommentRequestDto request = new CreateCommentRequestDto();
      request.setContent("   ");

      // When / Then
      assertThatThrownBy(() -> commentService.createComment(AUTHOR_ID, POST_ID, request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("must not be blank");
      verifyNoInteractions(postRepository, commentRepository);
    }

    @Test
    @DisplayName("should throw NotFoundException when the post does not exist")
    void shouldThrowNotFoundException_whenPostDoesNotExist() {
      // Given
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());
      CreateCommentRequestDto request = new CreateCommentRequestDto();
      request.setContent("Nice post!");

      // When / Then
      assertThatThrownBy(() -> commentService.createComment(AUTHOR_ID, POST_ID, request))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Post not found");
    }

    @Test
    @DisplayName("should throw UserBannedException when the commenter is banned")
    void shouldThrowUserBannedException_whenCommenterIsBanned() {
      // Given
      OffsetDateTime expiry = OffsetDateTime.now().plusDays(1);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(true);
      when(userBanService.getBanExpiry(AUTHOR_ID)).thenReturn(expiry);
      CreateCommentRequestDto request = new CreateCommentRequestDto();
      request.setContent("Nice post!");

      // When / Then
      assertThatThrownBy(() -> commentService.createComment(AUTHOR_ID, POST_ID, request))
          .isInstanceOf(UserBannedException.class);
      verifyNoInteractions(postRepository, commentRepository, notificationService);
    }

    @Test
    @DisplayName("should fall back to a generic name when the commenter's full name is blank")
    void shouldFallBackToGenericName_whenCommenterFullNameIsBlank() {
      // Given
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(2)));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(sampleUser(" ")));
      CreateCommentRequestDto request = new CreateCommentRequestDto();
      request.setContent("Nice post!");

      // When
      commentService.createComment(AUTHOR_ID, POST_ID, request);

      // Then
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getBody()).contains("Someone");
    }

    @Test
    @DisplayName("should fall back to a generic name when the commenter is not found")
    void shouldFallBackToGenericName_whenCommenterIsNotFound() {
      // Given
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(2)));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.empty());
      CreateCommentRequestDto request = new CreateCommentRequestDto();
      request.setContent("Nice post!");

      // When
      commentService.createComment(AUTHOR_ID, POST_ID, request);

      // Then
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getBody()).contains("Someone");
    }
  }

  // =====================================================================
  // updateComment
  // =====================================================================

  @Nested
  @DisplayName("updateComment")
  class UpdateCommentTests {

    @Test
    @DisplayName("should update the comment's content when the actor is its author")
    void shouldUpdateContent_whenActorIsAuthor() {
      // Given
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.existsById(POST_ID)).thenReturn(true);
      when(commentRepository.findById(COMMENT_ID))
          .thenReturn(Optional.of(sampleComment(AUTHOR_ID, null)));
      UpdateCommentRequestDto request = new UpdateCommentRequestDto();
      request.setContent("Edited content");

      // When
      commentService.updateComment(AUTHOR_ID, POST_ID, COMMENT_ID, request);

      // Then
      verify(commentRepository).save(commentCaptor.capture());
      assertThat(commentCaptor.getValue().getContent()).isEqualTo("Edited content");
    }

    @Test
    @DisplayName("should throw ForbiddenException when the actor is not the comment's author")
    void shouldThrowForbiddenException_whenActorIsNotAuthor() {
      // Given
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.existsById(POST_ID)).thenReturn(true);
      when(commentRepository.findById(COMMENT_ID))
          .thenReturn(Optional.of(sampleComment(999, null)));
      UpdateCommentRequestDto request = new UpdateCommentRequestDto();
      request.setContent("Edited content");

      // When / Then
      assertThatThrownBy(
              () -> commentService.updateComment(AUTHOR_ID, POST_ID, COMMENT_ID, request))
          .isInstanceOf(ForbiddenException.class);
      verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("should throw NotFoundException when the comment belongs to a different post")
    void shouldThrowNotFoundException_whenCommentBelongsToDifferentPost() {
      // Given
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.existsById(POST_ID)).thenReturn(true);
      CommentEntity commentOnOtherPost = sampleComment(AUTHOR_ID, null);
      commentOnOtherPost.setPostId(999);
      when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(commentOnOtherPost));
      UpdateCommentRequestDto request = new UpdateCommentRequestDto();
      request.setContent("Edited content");

      // When / Then
      assertThatThrownBy(
              () -> commentService.updateComment(AUTHOR_ID, POST_ID, COMMENT_ID, request))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should throw NotFoundException when the comment does not exist")
    void shouldThrowNotFoundException_whenCommentDoesNotExist() {
      // Given
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.existsById(POST_ID)).thenReturn(true);
      when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.empty());
      UpdateCommentRequestDto request = new UpdateCommentRequestDto();
      request.setContent("Edited content");

      // When / Then
      assertThatThrownBy(
              () -> commentService.updateComment(AUTHOR_ID, POST_ID, COMMENT_ID, request))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should throw NotFoundException when the post does not exist")
    void shouldThrowNotFoundException_whenPostDoesNotExist() {
      // Given
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.existsById(POST_ID)).thenReturn(false);
      UpdateCommentRequestDto request = new UpdateCommentRequestDto();
      request.setContent("Edited content");

      // When / Then
      assertThatThrownBy(
              () -> commentService.updateComment(AUTHOR_ID, POST_ID, COMMENT_ID, request))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Post not found");
      verify(commentRepository, never()).findById(any());
    }

    @Test
    @DisplayName("should throw ValidationException when content is blank")
    void shouldThrowValidationException_whenContentIsBlank() {
      // Given
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      UpdateCommentRequestDto request = new UpdateCommentRequestDto();
      request.setContent("");

      // When / Then
      assertThatThrownBy(
              () -> commentService.updateComment(AUTHOR_ID, POST_ID, COMMENT_ID, request))
          .isInstanceOf(ValidationException.class);
      verifyNoInteractions(postRepository, commentRepository);
    }
  }

  // =====================================================================
  // deleteComment
  // =====================================================================

  @Nested
  @DisplayName("deleteComment")
  class DeleteCommentTests {

    @Test
    @DisplayName("should push the decremented comment total into the feed cache")
    void shouldRefreshCachedCommentCount_whenCommentIsDeleted() {
      // Given
      when(postRepository.existsById(POST_ID)).thenReturn(true);
      when(commentRepository.findById(COMMENT_ID))
          .thenReturn(Optional.of(sampleComment(AUTHOR_ID, null)));
      when(commentRepository.countByPostId(POST_ID)).thenReturn(5L);

      // When
      commentService.deleteComment(AUTHOR_ID, POST_ID, COMMENT_ID);

      // Then — deleting has to move the number too, not just creating
      verify(newsfeedService).updateCachedCommentCount(POST_ID, 5);
    }

    @Test
    @DisplayName("should delete the comment when the actor is its author")
    void shouldDeleteComment_whenActorIsAuthor() {
      // Given
      when(postRepository.existsById(POST_ID)).thenReturn(true);
      when(commentRepository.findById(COMMENT_ID))
          .thenReturn(Optional.of(sampleComment(AUTHOR_ID, null)));

      // When
      commentService.deleteComment(AUTHOR_ID, POST_ID, COMMENT_ID);

      // Then
      verify(commentRepository).delete(any(CommentEntity.class));
    }

    @Test
    @DisplayName("should throw ForbiddenException when the actor is not the comment's author")
    void shouldThrowForbiddenException_whenActorIsNotAuthor() {
      // Given
      when(postRepository.existsById(POST_ID)).thenReturn(true);
      when(commentRepository.findById(COMMENT_ID))
          .thenReturn(Optional.of(sampleComment(999, null)));

      // When / Then
      assertThatThrownBy(() -> commentService.deleteComment(AUTHOR_ID, POST_ID, COMMENT_ID))
          .isInstanceOf(ForbiddenException.class);
      verify(commentRepository, never()).delete(any());
    }

    @Test
    @DisplayName("should throw NotFoundException when the comment does not exist")
    void shouldThrowNotFoundException_whenCommentDoesNotExist() {
      // Given
      when(postRepository.existsById(POST_ID)).thenReturn(true);
      when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> commentService.deleteComment(AUTHOR_ID, POST_ID, COMMENT_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should throw NotFoundException when the post does not exist")
    void shouldThrowNotFoundException_whenPostDoesNotExist() {
      // Given
      when(postRepository.existsById(POST_ID)).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> commentService.deleteComment(AUTHOR_ID, POST_ID, COMMENT_ID))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Post not found");
      verify(commentRepository, never()).findById(any());
    }
  }

  private static UserEntity sampleUser(String fullName) {
    UserEntity user = new UserEntity();
    user.setId(AUTHOR_ID);
    user.setFullName(fullName);
    return user;
  }
}
