package com.socialapp.posts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
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
import org.springframework.data.domain.Pageable;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.moderation.exception.UserBannedException;
import com.socialapp.moderation.service.UserBanService;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.posts.dto.ReactorPageResponseDto;
import com.socialapp.posts.dto.UpsertPostReactionRequestDto;
import com.socialapp.posts.entity.CommentEntity;
import com.socialapp.posts.entity.CommentReactionEntity;
import com.socialapp.posts.entity.CommentReactionId;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.ReactionType;
import com.socialapp.posts.repository.CommentReactionRepository;
import com.socialapp.posts.repository.CommentRepository;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.security.dto.PublicUserResponse;
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
  // getReactors
  // =====================================================================

  @Nested
  @DisplayName("getReactors")
  class GetReactorsTests {

    private UserEntity reactor(Integer id, String username) {
      UserEntity user = new UserEntity();
      user.setId(id);
      user.setUsername(username);
      user.setFullName("Reactor " + id);
      return user;
    }

    @Test
    @DisplayName("should return one page of reactors with the total for the filter")
    void shouldReturnAPageOfReactors() {
      // Given
      givenReadableComment(COMMENT_AUTHOR_ID);
      when(commentReactionRepository.findReactorIds(eq(COMMENT_ID), isNull(), isNull(), any()))
          .thenReturn(List.of(11, 12));
      when(userRepository.findAllById(List.of(11, 12)))
          .thenReturn(List.of(reactor(11, "ada"), reactor(12, "bob")));
      when(commentReactionRepository.countByIdCommentId(COMMENT_ID)).thenReturn(2L);

      // When
      ReactorPageResponseDto page =
          commentReactionService.getReactors(USER_ID, POST_ID, COMMENT_ID, null, null, 20);

      // Then - totalCount is the total for the whole filter, not for the page, so the UI can say
      // "2 reactions" without walking the cursor
      assertThat(page.reactors())
          .extracting(PublicUserResponse::username)
          .containsExactly("ada", "bob");
      assertThat(page.totalCount()).isEqualTo(2L);
      assertThat(page.hasMore()).isFalse();
      assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("should ask for one row more than the limit, and report the extra as hasMore")
    void shouldDetectAFurtherPage() {
      // Given - limit + 1 detects a further page without a second count query over the same rows
      givenReadableComment(COMMENT_AUTHOR_ID);
      when(commentReactionRepository.findReactorIds(eq(COMMENT_ID), isNull(), isNull(), any()))
          .thenReturn(List.of(11, 12, 13));
      when(userRepository.findAllById(List.of(11, 12)))
          .thenReturn(List.of(reactor(11, "ada"), reactor(12, "bob")));
      when(commentReactionRepository.countByIdCommentId(COMMENT_ID)).thenReturn(3L);

      // When
      ReactorPageResponseDto page =
          commentReactionService.getReactors(USER_ID, POST_ID, COMMENT_ID, null, null, 2);

      // Then - the extra row is dropped from the page and becomes the cursor
      ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
      verify(commentReactionRepository)
          .findReactorIds(eq(COMMENT_ID), isNull(), isNull(), pageable.capture());
      assertThat(pageable.getValue().getPageSize()).isEqualTo(3);
      assertThat(page.reactors()).hasSize(2);
      assertThat(page.hasMore()).isTrue();
      assertThat(page.nextCursor()).isEqualTo(12);
    }

    @Test
    @DisplayName("should count only the requested type when one is given")
    void shouldCountOnlyTheFilteredType() {
      // Given
      givenReadableComment(COMMENT_AUTHOR_ID);
      when(commentReactionRepository.findReactorIds(
              eq(COMMENT_ID), eq(ReactionType.CLAP), isNull(), any()))
          .thenReturn(List.of(11));
      when(userRepository.findAllById(List.of(11))).thenReturn(List.of(reactor(11, "ada")));
      when(commentReactionRepository.countByIdCommentIdAndReactionType(
              COMMENT_ID, ReactionType.CLAP))
          .thenReturn(1L);

      // When
      ReactorPageResponseDto page =
          commentReactionService.getReactors(
              USER_ID, POST_ID, COMMENT_ID, ReactionType.CLAP, null, 20);

      // Then - the unfiltered total would be the wrong number to show above a filtered list
      assertThat(page.totalCount()).isEqualTo(1L);
      verify(commentReactionRepository, never()).countByIdCommentId(any());
    }

    @Test
    @DisplayName("should keep the order of the id list and skip a reactor whose account is gone")
    void shouldPreserveOrderAndSkipDeletedAccounts() {
      // Given - findAllById gives no ordering guarantee, and a deleted account must not render as
      // a blank row
      givenReadableComment(COMMENT_AUTHOR_ID);
      when(commentReactionRepository.findReactorIds(eq(COMMENT_ID), isNull(), isNull(), any()))
          .thenReturn(List.of(11, 12, 13));
      when(userRepository.findAllById(List.of(11, 12, 13)))
          .thenReturn(List.of(reactor(13, "cleo"), reactor(11, "ada")));
      when(commentReactionRepository.countByIdCommentId(COMMENT_ID)).thenReturn(3L);

      // When
      ReactorPageResponseDto page =
          commentReactionService.getReactors(USER_ID, POST_ID, COMMENT_ID, null, null, 20);

      // Then
      assertThat(page.reactors())
          .extracting(PublicUserResponse::username)
          .containsExactly("ada", "cleo");
    }

    @Test
    @DisplayName("should refuse when the post is not visible to the caller")
    void shouldRefuse_whenPostNotVisible() {
      // Given - without this the endpoint enumerates everyone who reacted to a comment on a
      // FRIENDS-only post, which is that post's audience list in all but name
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost()));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(false);

      // When / Then - 404, not 403: a thing you may not see must look like one that is not there
      assertThatThrownBy(
              () ->
                  commentReactionService.getReactors(USER_ID, POST_ID, COMMENT_ID, null, null, 20))
          .isInstanceOf(NotFoundException.class);
      verify(commentReactionRepository, never()).findReactorIds(any(), any(), any(), any());
    }

    @Test
    @DisplayName("should refuse a comment that belongs to a different post")
    void shouldRefuse_whenCommentBelongsToAnotherPost() {
      // Given - otherwise /posts/{readable}/comments/{someone-elses}/reactions reaches a comment
      // under a post the caller cannot see, by borrowing the id of one they can
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(samplePost()));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      when(commentRepository.findById(COMMENT_ID))
          .thenReturn(Optional.of(sampleComment(COMMENT_AUTHOR_ID, 999)));

      // When / Then
      assertThatThrownBy(
              () ->
                  commentReactionService.getReactors(USER_ID, POST_ID, COMMENT_ID, null, null, 20))
          .isInstanceOf(NotFoundException.class);
    }
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
