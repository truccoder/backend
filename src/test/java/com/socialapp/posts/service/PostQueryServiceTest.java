package com.socialapp.posts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
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
import org.springframework.data.domain.Pageable;

import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.newsfeed.dto.FeedPostDataDto;
import com.socialapp.newsfeed.service.FeedPostDataMapper;
import com.socialapp.posts.dto.PostPageResponseDto;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link PostQueryService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 4.3.2 branch testing, Section 2.1.3 BDD Given/When/Then).
 */
@ExtendWith(MockitoExtension.class)
class PostQueryServiceTest {

  private static final Integer AUTHOR_ID = 1;
  private static final Integer VIEWER_ID = 2;
  private static final Integer POST_ID = 100;

  @Mock private PostRepository postRepository;
  @Mock private UserRepository userRepository;
  @Mock private PostVisibilityService postVisibilityService;
  @Mock private BlockQueryService blockQueryService;
  @Mock private FeedPostDataMapper feedPostDataMapper;

  @InjectMocks private PostQueryService postQueryService;

  @Captor private ArgumentCaptor<List<ModerationStatus>> statusesCaptor;
  @Captor private ArgumentCaptor<List<PostEntity>> pageCaptor;

  private static PostEntity post(Integer id) {
    PostEntity post = new PostEntity();
    post.setId(id);
    post.setAuthorId(AUTHOR_ID);
    post.setVisibility(PostVisibility.PUBLIC);
    post.setModerationStatus(ModerationStatus.APPROVED);
    return post;
  }

  private static FeedPostDataDto feedPost(Integer id) {
    return FeedPostDataDto.builder().postId(id).authorId(AUTHOR_ID).build();
  }

  @Nested
  @DisplayName("getPost")
  class GetPost {

    @Test
    @DisplayName("returns the feed-shaped payload when the viewer may see the post")
    void returnsPayload() {
      // Given
      PostEntity post = post(POST_ID);
      when(postRepository.findById(POST_ID)).thenReturn(java.util.Optional.of(post));
      when(postVisibilityService.isVisibleTo(post, VIEWER_ID)).thenReturn(true);
      when(feedPostDataMapper.toFeedPostData(post)).thenReturn(feedPost(POST_ID));

      // When
      FeedPostDataDto result = postQueryService.getPost(VIEWER_ID, POST_ID);

      // Then
      assertThat(result.getPostId()).isEqualTo(POST_ID);
      verify(feedPostDataMapper).signBookCover(result);
    }

    @Test
    @DisplayName("throws NotFound — not Forbidden — when the viewer may not see the post")
    void hidesPostAsMissing() {
      // Given: the post exists, but this viewer is not allowed to read it
      PostEntity post = post(POST_ID);
      when(postRepository.findById(POST_ID)).thenReturn(java.util.Optional.of(post));
      when(postVisibilityService.isVisibleTo(post, VIEWER_ID)).thenReturn(false);

      // When / Then: reported as missing, so the response cannot be used to prove it exists
      assertThatThrownBy(() -> postQueryService.getPost(VIEWER_ID, POST_ID))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining(String.valueOf(POST_ID));
    }

    @Test
    @DisplayName("throws NotFound when the post does not exist at all")
    void throwsWhenMissing() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(java.util.Optional.empty());

      // When / Then
      assertThatThrownBy(() -> postQueryService.getPost(VIEWER_ID, POST_ID))
          .isInstanceOf(NotFoundException.class);
    }
  }

  @Nested
  @DisplayName("getPostsByAuthor")
  class GetPostsByAuthor {

    @Test
    @DisplayName("throws NotFound when the author does not exist")
    void throwsForUnknownAuthor() {
      // Given
      when(userRepository.existsById(AUTHOR_ID)).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> postQueryService.getPostsByAuthor(VIEWER_ID, AUTHOR_ID, null, 20))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("returns an empty page and reads nothing when a block stands between the two")
    void returnsEmptyPageWhenBlocked() {
      // Given
      when(userRepository.existsById(AUTHOR_ID)).thenReturn(true);
      when(postVisibilityService.isBlocked(VIEWER_ID, AUTHOR_ID)).thenReturn(true);

      // When
      PostPageResponseDto page = postQueryService.getPostsByAuthor(VIEWER_ID, AUTHOR_ID, null, 20);

      // Then
      assertThat(page.posts()).isEmpty();
      assertThat(page.hasMore()).isFalse();
      assertThat(page.nextCursor()).isNull();
      verify(postRepository, never())
          .findByAuthorForViewer(any(), anyList(), anyList(), any(), any());
    }

    @Test
    @DisplayName("asks only for APPROVED posts when the viewer is not the author")
    void restrictsModerationStatusForOtherViewers() {
      // Given
      when(userRepository.existsById(AUTHOR_ID)).thenReturn(true);
      when(postVisibilityService.isBlocked(VIEWER_ID, AUTHOR_ID)).thenReturn(false);
      when(postVisibilityService.visibleVisibilities(VIEWER_ID, AUTHOR_ID))
          .thenReturn(List.of(PostVisibility.PUBLIC));
      when(postRepository.findByAuthorForViewer(
              eq(AUTHOR_ID), anyList(), statusesCaptor.capture(), eq(null), any(Pageable.class)))
          .thenReturn(List.of());
      when(feedPostDataMapper.toFeedPostDataPage(anyList())).thenReturn(List.of());

      // When
      postQueryService.getPostsByAuthor(VIEWER_ID, AUTHOR_ID, null, 20);

      // Then
      assertThat(statusesCaptor.getValue()).containsExactly(ModerationStatus.APPROVED);
    }

    @Test
    @DisplayName("lets the author see their own posts in every moderation status")
    void authorSeesEveryStatus() {
      // Given
      when(userRepository.existsById(AUTHOR_ID)).thenReturn(true);
      when(postVisibilityService.visibleVisibilities(AUTHOR_ID, AUTHOR_ID))
          .thenReturn(List.of(PostVisibility.values()));
      when(postRepository.findByAuthorForViewer(
              eq(AUTHOR_ID), anyList(), statusesCaptor.capture(), eq(null), any(Pageable.class)))
          .thenReturn(List.of());
      when(feedPostDataMapper.toFeedPostDataPage(anyList())).thenReturn(List.of());

      // When
      postQueryService.getPostsByAuthor(AUTHOR_ID, AUTHOR_ID, null, 20);

      // Then
      assertThat(statusesCaptor.getValue()).containsExactlyInAnyOrder(ModerationStatus.values());
    }

    @Test
    @DisplayName("trims the extra row, reports hasMore and hands back the last id as the cursor")
    void trimsAndReportsCursor() {
      // Given: the repository is asked for limit + 1 rows and returns all three
      when(userRepository.existsById(AUTHOR_ID)).thenReturn(true);
      when(postVisibilityService.isBlocked(VIEWER_ID, AUTHOR_ID)).thenReturn(false);
      when(postVisibilityService.visibleVisibilities(VIEWER_ID, AUTHOR_ID))
          .thenReturn(List.of(PostVisibility.PUBLIC));
      when(postRepository.findByAuthorForViewer(
              eq(AUTHOR_ID), anyList(), anyList(), eq(null), any(Pageable.class)))
          .thenReturn(List.of(post(3), post(2), post(1)));
      when(feedPostDataMapper.toFeedPostDataPage(pageCaptor.capture()))
          .thenReturn(List.of(feedPost(3), feedPost(2)));

      // When
      PostPageResponseDto page = postQueryService.getPostsByAuthor(VIEWER_ID, AUTHOR_ID, null, 2);

      // Then
      assertThat(pageCaptor.getValue()).extracting(PostEntity::getId).containsExactly(3, 2);
      assertThat(page.hasMore()).isTrue();
      assertThat(page.nextCursor()).isEqualTo(2);
    }

    @Test
    @DisplayName("reports no further page and a null cursor when the rows fit inside the limit")
    void noCursorWhenPageFits() {
      // Given
      when(userRepository.existsById(AUTHOR_ID)).thenReturn(true);
      when(postVisibilityService.isBlocked(VIEWER_ID, AUTHOR_ID)).thenReturn(false);
      when(postVisibilityService.visibleVisibilities(VIEWER_ID, AUTHOR_ID))
          .thenReturn(List.of(PostVisibility.PUBLIC));
      when(postRepository.findByAuthorForViewer(
              eq(AUTHOR_ID), anyList(), anyList(), eq(null), any(Pageable.class)))
          .thenReturn(List.of(post(3)));
      when(feedPostDataMapper.toFeedPostDataPage(anyList())).thenReturn(List.of(feedPost(3)));

      // When
      PostPageResponseDto page = postQueryService.getPostsByAuthor(VIEWER_ID, AUTHOR_ID, null, 2);

      // Then
      assertThat(page.hasMore()).isFalse();
      assertThat(page.nextCursor()).isNull();
    }
  }

  @Nested
  @DisplayName("getPublicFeed")
  class GetPublicFeed {

    @Test
    @DisplayName("passes a sentinel id when the viewer has blocked nobody, so NOT IN stays valid")
    void passesSentinelWhenNoBlocks() {
      // Given
      when(blockQueryService.blockedPairIds(VIEWER_ID)).thenReturn(Set.of());
      ArgumentCaptor<java.util.Collection<Integer>> excluded =
          ArgumentCaptor.forClass(java.util.Collection.class);
      when(postRepository.findPublicFeed(excluded.capture(), eq(null), any(Pageable.class)))
          .thenReturn(List.of());
      when(feedPostDataMapper.toFeedPostDataPage(anyList())).thenReturn(List.of());

      // When
      postQueryService.getPublicFeed(VIEWER_ID, null, 20);

      // Then
      assertThat(excluded.getValue()).containsExactly(-1);
    }

    @Test
    @DisplayName("excludes the viewer's blocked pair from the query")
    void passesBlockSet() {
      // Given
      when(blockQueryService.blockedPairIds(VIEWER_ID)).thenReturn(Set.of(7, 8));
      ArgumentCaptor<java.util.Collection<Integer>> excluded =
          ArgumentCaptor.forClass(java.util.Collection.class);
      when(postRepository.findPublicFeed(excluded.capture(), eq(null), any(Pageable.class)))
          .thenReturn(List.of());
      when(feedPostDataMapper.toFeedPostDataPage(anyList())).thenReturn(List.of());

      // When
      postQueryService.getPublicFeed(VIEWER_ID, null, 20);

      // Then
      assertThat(excluded.getValue()).containsExactlyInAnyOrder(7, 8);
    }
  }
}
