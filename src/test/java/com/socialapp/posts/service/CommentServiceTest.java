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

import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.moderation.exception.UserBannedException;
import com.socialapp.moderation.service.UserBanService;
import com.socialapp.newsfeed.entity.enums.InteractionType;
import com.socialapp.newsfeed.service.NewsfeedService;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.posts.dto.CommentResponseDto;
import com.socialapp.posts.dto.CreateCommentRequestDto;
import com.socialapp.posts.dto.UpdateCommentRequestDto;
import com.socialapp.posts.entity.CommentEntity;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.ReactionType;
import com.socialapp.posts.repository.CommentReactionRepository;
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
  @Mock private CommentReactionRepository commentReactionRepository;
  @Mock private PostRepository postRepository;
  @Mock private UserBanService userBanService;
  @Mock private UserRepository userRepository;
  @Mock private NotificationService notificationService;
  @Mock private NewsfeedService newsfeedService;
  @Mock private BlockQueryService blockQueryService;
  @Mock private PostVisibilityService postVisibilityService;

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
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
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
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
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
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      CreateCommentRequestDto request = new CreateCommentRequestDto();
      request.setContent("My own comment");

      // When
      commentService.createComment(AUTHOR_ID, POST_ID, request);

      // Then
      verify(commentRepository).save(any());
      verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("should record a COMMENT interaction so the author gains feed affinity")
    void shouldTrackInteraction_whenCommentIsCreated() {
      // Given — trackInteraction had no production caller at all, so t_user_interactions stayed
      // empty and the affinity term of the feed ranking formula was always exactly zero
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(2)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(sampleUser("Alice")));
      CreateCommentRequestDto request = new CreateCommentRequestDto();
      request.setContent("Nice post!");

      // When
      commentService.createComment(AUTHOR_ID, POST_ID, request);

      // Then
      verify(newsfeedService).trackInteraction(AUTHOR_ID, POST_ID, 2, InteractionType.COMMENT);
    }

    @Test
    @DisplayName("should not record an interaction when the author comments on their own post")
    void shouldNotTrackInteraction_whenAuthorCommentsOnOwnPost() {
      // Given — affinity with yourself would boost your own posts in your own feed
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(AUTHOR_ID)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      CreateCommentRequestDto request = new CreateCommentRequestDto();
      request.setContent("My own comment");

      // When
      commentService.createComment(AUTHOR_ID, POST_ID, request);

      // Then
      verify(newsfeedService, never()).trackInteraction(any(), any(), any(), any());
    }

    @Test
    @DisplayName("should save a valid reply to a top-level comment")
    void shouldSaveReply_whenParentIsTopLevelComment() {
      // Given
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(AUTHOR_ID)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
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
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
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
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
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
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
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
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
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
  // post visibility gate
  // =====================================================================

  @Nested
  @DisplayName("post visibility")
  class PostVisibilityTests {

    @Test
    @DisplayName("should not show the comment thread on a post the viewer may not read")
    void shouldRefuseGetComments_whenPostNotVisible() {
      // Given: a post the viewer cannot see. The thread carries comment bodies plus every
      // commenter's name and avatar, so it leaks more than the post's existence.
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(4242)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> commentService.getComments(5, POST_ID))
          .isInstanceOf(NotFoundException.class);
      verify(commentRepository, never()).findByPostIdOrderByCreatedAtAsc(any());
    }

    @Test
    @DisplayName("should not let a comment be written on a post the author may not read")
    void shouldRefuseCreateComment_whenPostNotVisible() {
      // Given: posting into a thread the caller cannot see also fires a notification at an author
      // who never shared the post with them.
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(4242)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(false);

      CreateCommentRequestDto request = new CreateCommentRequestDto();
      request.setContent("let me in");

      // When / Then
      assertThatThrownBy(() -> commentService.createComment(AUTHOR_ID, POST_ID, request))
          .isInstanceOf(NotFoundException.class);
      verify(commentRepository, never()).save(any());
      verifyNoInteractions(notificationService);
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
    user.setUsername("author_" + AUTHOR_ID);
    user.setFullName(fullName);
    return user;
  }

  @Nested
  @DisplayName("getComments — block filtering")
  class GetCommentsBlockFilteringTests {

    @Test
    @DisplayName("should hide comments written by someone in the viewer's block set")
    void shouldHideBlockedAuthorsComments() {
      // Given: two comments, one by a user the viewer has blocked (or who blocked the viewer)
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(AUTHOR_ID)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      CommentEntity mine = new CommentEntity();
      mine.setId(1);
      mine.setPostId(POST_ID);
      mine.setAuthorId(7);
      mine.setContent("visible");
      CommentEntity blocked = new CommentEntity();
      blocked.setId(2);
      blocked.setPostId(POST_ID);
      blocked.setAuthorId(8);
      blocked.setContent("hidden");
      when(commentRepository.findByPostIdOrderByCreatedAtAsc(POST_ID))
          .thenReturn(java.util.List.of(mine, blocked));
      when(blockQueryService.blockedPairIds(5)).thenReturn(java.util.Set.of(8));
      when(userRepository.findAllById(java.util.Set.of(7))).thenReturn(java.util.List.of());
      when(commentReactionRepository.countByCommentIds(java.util.List.of(1)))
          .thenReturn(java.util.Map.of());
      when(commentReactionRepository.findMyReactions(5, java.util.List.of(1)))
          .thenReturn(java.util.Map.of());

      // When
      var comments = commentService.getComments(5, POST_ID);

      // Then — a comment thread is where a blocked user can talk straight at the person who
      // blocked them, so leaving this unfiltered would undo most of what the block is for
      assertThat(comments).extracting(CommentResponseDto::getId).containsExactly(1);
    }
  }

  @Nested
  @DisplayName("getComments — reaction counts and the caller's own reaction")
  class GetCommentsReactionTests {

    private CommentEntity comment(Integer id, Integer authorId) {
      CommentEntity comment = new CommentEntity();
      comment.setId(id);
      comment.setPostId(POST_ID);
      comment.setAuthorId(authorId);
      comment.setContent("content " + id);
      return comment;
    }

    @Test
    @DisplayName("should attach the reaction total and the caller's own reaction to each comment")
    void shouldAttachReactionFields() {
      // Given — likeCount is what the "two most-reacted comments" preview ranks by; before it the
      // preview fell back to the two oldest and said so on screen
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(AUTHOR_ID)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      when(commentRepository.findByPostIdOrderByCreatedAtAsc(POST_ID))
          .thenReturn(java.util.List.of(comment(1, AUTHOR_ID), comment(2, AUTHOR_ID)));
      when(blockQueryService.blockedPairIds(5)).thenReturn(java.util.Set.of());
      when(userRepository.findAllById(java.util.Set.of(AUTHOR_ID)))
          .thenReturn(java.util.List.of(sampleUser("Author")));
      when(commentReactionRepository.countByCommentIds(java.util.List.of(1, 2)))
          .thenReturn(java.util.Map.of(1, 5L));
      when(commentReactionRepository.findMyReactions(5, java.util.List.of(1, 2)))
          .thenReturn(java.util.Map.of(2, ReactionType.INSIGHT));

      // When
      var comments = commentService.getComments(5, POST_ID);

      // Then — a comment nobody reacted to reads 0 rather than null, and myReaction stays null
      // where the caller has not chosen anything
      assertThat(comments.get(0).getLikeCount()).isEqualTo(5);
      assertThat(comments.get(0).getMyReaction()).isNull();
      assertThat(comments.get(1).getLikeCount()).isZero();
      assertThat(comments.get(1).getMyReaction()).isEqualTo(ReactionType.INSIGHT);
    }

    @Test
    @DisplayName("should read both maps in one batch each, never once per comment")
    void shouldQueryOncePerThreadNotOncePerComment() {
      // Given — a thread has no upper bound, so a count inside the mapping loop is the N+1 that
      // hurts most on exactly the posts people are reading
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(AUTHOR_ID)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      when(commentRepository.findByPostIdOrderByCreatedAtAsc(POST_ID))
          .thenReturn(
              java.util.List.of(
                  comment(1, AUTHOR_ID), comment(2, AUTHOR_ID), comment(3, AUTHOR_ID)));
      when(blockQueryService.blockedPairIds(5)).thenReturn(java.util.Set.of());
      when(userRepository.findAllById(java.util.Set.of(AUTHOR_ID)))
          .thenReturn(java.util.List.of(sampleUser("Author")));
      when(commentReactionRepository.countByCommentIds(any())).thenReturn(java.util.Map.of());
      when(commentReactionRepository.findMyReactions(any(), any())).thenReturn(java.util.Map.of());

      // When
      commentService.getComments(5, POST_ID);

      // Then
      verify(commentReactionRepository, times(1)).countByCommentIds(java.util.List.of(1, 2, 3));
      verify(commentReactionRepository, times(1)).findMyReactions(5, java.util.List.of(1, 2, 3));
    }

    @Test
    @DisplayName("should carry the commenter's username, so their name can link to their profile")
    void shouldCarryAuthorUsername() {
      // Given — the same gap the feed had, one level down
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(AUTHOR_ID)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      when(commentRepository.findByPostIdOrderByCreatedAtAsc(POST_ID))
          .thenReturn(java.util.List.of(comment(1, AUTHOR_ID)));
      when(blockQueryService.blockedPairIds(5)).thenReturn(java.util.Set.of());
      when(userRepository.findAllById(java.util.Set.of(AUTHOR_ID)))
          .thenReturn(java.util.List.of(sampleUser("Author")));
      when(commentReactionRepository.countByCommentIds(any())).thenReturn(java.util.Map.of());
      when(commentReactionRepository.findMyReactions(any(), any())).thenReturn(java.util.Map.of());

      // When
      var comments = commentService.getComments(5, POST_ID);

      // Then
      assertThat(comments.get(0).getAuthorUsername()).isEqualTo("author_" + AUTHOR_ID);
    }

    @Test
    @DisplayName("should leave the author fields null when the user row is gone")
    void shouldTolerateAMissingAuthorRow() {
      // Given — a comment outlives its author's account; the thread must still render rather
      // than throw, which is why every author field is read through a null guard
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost(AUTHOR_ID)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      when(commentRepository.findByPostIdOrderByCreatedAtAsc(POST_ID))
          .thenReturn(java.util.List.of(comment(1, AUTHOR_ID)));
      when(blockQueryService.blockedPairIds(5)).thenReturn(java.util.Set.of());
      when(userRepository.findAllById(java.util.Set.of(AUTHOR_ID))).thenReturn(java.util.List.of());
      when(commentReactionRepository.countByCommentIds(any())).thenReturn(java.util.Map.of());
      when(commentReactionRepository.findMyReactions(any(), any())).thenReturn(java.util.Map.of());

      // When
      var comments = commentService.getComments(5, POST_ID);

      // Then
      assertThat(comments.get(0).getAuthorUsername()).isNull();
      assertThat(comments.get(0).getAuthorFullName()).isNull();
    }
  }
}
