package com.socialapp.posts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.friendships.service.FriendshipService;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.PostVisibility;

/**
 * Component (unit) tests for {@link PostVisibilityService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 4.3.2 branch testing, Section 2.1.3 BDD Given/When/Then).
 *
 * <p>This class is the whole of the "who may read this post" rule, so the cases below are written
 * as the three roles the product defines — stranger, friend, author — crossed with the three
 * visibilities, plus moderation and blocking. A gap here is a leak everywhere the rule is used.
 */
@ExtendWith(MockitoExtension.class)
class PostVisibilityServiceTest {

  private static final Integer AUTHOR_ID = 1;
  private static final Integer FRIEND_ID = 2;
  private static final Integer STRANGER_ID = 3;

  @Mock private FriendshipService friendshipService;
  @Mock private BlockQueryService blockQueryService;

  @InjectMocks private PostVisibilityService postVisibilityService;

  private static PostEntity post(PostVisibility visibility, ModerationStatus status) {
    PostEntity post = new PostEntity();
    post.setId(100);
    post.setAuthorId(AUTHOR_ID);
    post.setVisibility(visibility);
    post.setModerationStatus(status);
    return post;
  }

  @Nested
  @DisplayName("isVisibleTo")
  class IsVisibleTo {

    @Test
    @DisplayName("a stranger sees a PUBLIC approved post")
    void strangerSeesPublic() {
      // Given
      PostEntity post = post(PostVisibility.PUBLIC, ModerationStatus.APPROVED);

      // When
      boolean visible = postVisibilityService.isVisibleTo(post, STRANGER_ID);

      // Then
      assertThat(visible).isTrue();
    }

    @Test
    @DisplayName("a stranger does not see a FRIENDS post")
    void strangerDoesNotSeeFriendsPost() {
      // Given
      PostEntity post = post(PostVisibility.FRIENDS, ModerationStatus.APPROVED);
      when(blockQueryService.isBlockedEitherWay(STRANGER_ID, AUTHOR_ID)).thenReturn(false);
      when(friendshipService.areFriends(STRANGER_ID, AUTHOR_ID)).thenReturn(false);

      // When
      boolean visible = postVisibilityService.isVisibleTo(post, STRANGER_ID);

      // Then
      assertThat(visible).isFalse();
    }

    @Test
    @DisplayName("a friend sees a FRIENDS post")
    void friendSeesFriendsPost() {
      // Given
      PostEntity post = post(PostVisibility.FRIENDS, ModerationStatus.APPROVED);
      when(blockQueryService.isBlockedEitherWay(FRIEND_ID, AUTHOR_ID)).thenReturn(false);
      when(friendshipService.areFriends(FRIEND_ID, AUTHOR_ID)).thenReturn(true);

      // When
      boolean visible = postVisibilityService.isVisibleTo(post, FRIEND_ID);

      // Then
      assertThat(visible).isTrue();
    }

    @Test
    @DisplayName("nobody but the author sees a PRIVATE post, not even a friend")
    void friendDoesNotSeePrivatePost() {
      // Given
      PostEntity post = post(PostVisibility.PRIVATE, ModerationStatus.APPROVED);
      when(blockQueryService.isBlockedEitherWay(FRIEND_ID, AUTHOR_ID)).thenReturn(false);

      // When
      boolean visible = postVisibilityService.isVisibleTo(post, FRIEND_ID);

      // Then
      assertThat(visible).isFalse();
      verify(friendshipService, never()).areFriends(FRIEND_ID, AUTHOR_ID);
    }

    @Test
    @DisplayName("the author sees their own PRIVATE post without any friendship lookup")
    void authorSeesOwnPrivatePost() {
      // Given
      PostEntity post = post(PostVisibility.PRIVATE, ModerationStatus.APPROVED);

      // When
      boolean visible = postVisibilityService.isVisibleTo(post, AUTHOR_ID);

      // Then
      assertThat(visible).isTrue();
      verify(friendshipService, never()).areFriends(AUTHOR_ID, AUTHOR_ID);
      verify(blockQueryService, never()).isBlockedEitherWay(AUTHOR_ID, AUTHOR_ID);
    }

    @Test
    @DisplayName("the author sees their own post while it is still in moderation")
    void authorSeesOwnPendingPost() {
      // Given
      PostEntity post = post(PostVisibility.PUBLIC, ModerationStatus.PENDING_MODERATION);

      // When / Then
      assertThat(postVisibilityService.isVisibleTo(post, AUTHOR_ID)).isTrue();
    }

    @Test
    @DisplayName("nobody else sees a post that moderation has not approved, however public it is")
    void othersDoNotSeeUnapprovedPost() {
      // Given
      PostEntity pending = post(PostVisibility.PUBLIC, ModerationStatus.PENDING_MODERATION);
      PostEntity rejected = post(PostVisibility.PUBLIC, ModerationStatus.REJECTED);

      // When / Then
      assertThat(postVisibilityService.isVisibleTo(pending, STRANGER_ID)).isFalse();
      assertThat(postVisibilityService.isVisibleTo(rejected, STRANGER_ID)).isFalse();
    }

    @Test
    @DisplayName("a block hides even a PUBLIC approved post, in either direction")
    void blockHidesPublicPost() {
      // Given
      PostEntity post = post(PostVisibility.PUBLIC, ModerationStatus.APPROVED);
      when(blockQueryService.isBlockedEitherWay(STRANGER_ID, AUTHOR_ID)).thenReturn(true);

      // When
      boolean visible = postVisibilityService.isVisibleTo(post, STRANGER_ID);

      // Then
      assertThat(visible).isFalse();
    }
  }

  @Nested
  @DisplayName("visibleVisibilities")
  class VisibleVisibilities {

    @Test
    @DisplayName("the author may see all three visibilities of their own posts")
    void authorSeesEverything() {
      // When
      var visibilities = postVisibilityService.visibleVisibilities(AUTHOR_ID, AUTHOR_ID);

      // Then
      assertThat(visibilities)
          .containsExactlyInAnyOrder(
              PostVisibility.PUBLIC, PostVisibility.FRIENDS, PostVisibility.PRIVATE);
    }

    @Test
    @DisplayName("a friend may see PUBLIC and FRIENDS, never PRIVATE")
    void friendSeesPublicAndFriends() {
      // Given
      when(friendshipService.areFriends(FRIEND_ID, AUTHOR_ID)).thenReturn(true);

      // When
      var visibilities = postVisibilityService.visibleVisibilities(FRIEND_ID, AUTHOR_ID);

      // Then
      assertThat(visibilities)
          .containsExactlyInAnyOrder(PostVisibility.PUBLIC, PostVisibility.FRIENDS);
    }

    @Test
    @DisplayName("a stranger may see PUBLIC only")
    void strangerSeesPublicOnly() {
      // Given
      when(friendshipService.areFriends(STRANGER_ID, AUTHOR_ID)).thenReturn(false);

      // When
      var visibilities = postVisibilityService.visibleVisibilities(STRANGER_ID, AUTHOR_ID);

      // Then
      assertThat(visibilities).containsExactly(PostVisibility.PUBLIC);
    }
  }

  @Nested
  @DisplayName("isBlocked")
  class IsBlocked {

    @Test
    @DisplayName("delegates to the block query service")
    void delegates() {
      // Given
      when(blockQueryService.isBlockedEitherWay(STRANGER_ID, AUTHOR_ID)).thenReturn(true);

      // When / Then
      assertThat(postVisibilityService.isBlocked(STRANGER_ID, AUTHOR_ID)).isTrue();
    }
  }

  @Nested
  @DisplayName("guest (null viewer)")
  class GuestViewer {

    @Test
    @DisplayName("sees a PUBLIC approved post")
    void guestSeesPublic() {
      // Given
      PostEntity post = post(PostVisibility.PUBLIC, ModerationStatus.APPROVED);

      // When / Then
      assertThat(postVisibilityService.isVisibleTo(post, null)).isTrue();
    }

    @Test
    @DisplayName("sees neither FRIENDS nor PRIVATE, and triggers no lookup at all")
    void guestSeesNothingElse() {
      // When / Then — the null case is answered before any repository call: a null user id is not
      // an error to those queries, it is a query that quietly matches nothing, which would be the
      // right answer by accident today and the wrong one after any refactor
      assertThat(
              postVisibilityService.isVisibleTo(
                  post(PostVisibility.FRIENDS, ModerationStatus.APPROVED), null))
          .isFalse();
      assertThat(
              postVisibilityService.isVisibleTo(
                  post(PostVisibility.PRIVATE, ModerationStatus.APPROVED), null))
          .isFalse();
      verifyNoInteractions(friendshipService);
      verify(blockQueryService, never())
          .isBlockedEitherWay(
              org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("does not see a post that moderation has not approved")
    void guestSeesNoUnapprovedPost() {
      // When / Then
      assertThat(
              postVisibilityService.isVisibleTo(
                  post(PostVisibility.PUBLIC, ModerationStatus.PENDING_MODERATION), null))
          .isFalse();
    }

    @Test
    @DisplayName("is offered PUBLIC only, with no friendship lookup")
    void guestVisibilitiesArePublicOnly() {
      // When
      var visibilities = postVisibilityService.visibleVisibilities(null, AUTHOR_ID);

      // Then
      assertThat(visibilities).containsExactly(PostVisibility.PUBLIC);
      verifyNoInteractions(friendshipService);
    }

    @Test
    @DisplayName("is never blocked, because a guest has no identity to block")
    void guestIsNeverBlocked() {
      // When / Then
      assertThat(postVisibilityService.isBlocked(null, AUTHOR_ID)).isFalse();
      verifyNoInteractions(blockQueryService);
    }
  }
}
