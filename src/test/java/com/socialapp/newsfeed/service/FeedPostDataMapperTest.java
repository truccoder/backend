package com.socialapp.newsfeed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.bookstore.repository.BookRepository;
import com.socialapp.bookstore.service.BookReviewService;
import com.socialapp.bookstore.service.BookStorageService;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.newsfeed.dto.FeedPostDataDto;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.posts.repository.CommentRepository;
import com.socialapp.posts.repository.PostReactionRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link FeedPostDataMapper}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 2.1.3 BDD Given/When/Then).
 *
 * <p>The payload-content assertions live in {@code NewsfeedServiceTest}, which drives this mapper
 * for real through fan-out. What is pinned here is what the page-at-a-time path adds: batched
 * counters instead of a query per row, and the handling of a post whose author row has gone.
 */
@ExtendWith(MockitoExtension.class)
class FeedPostDataMapperTest {

  private static final Integer AUTHOR_ID = 1;

  @Mock private UserRepository userRepository;
  @Mock private BookRepository bookRepository;
  @Mock private BookReviewService bookReviewService;
  @Mock private BookStorageService bookStorageService;
  @Mock private PostReactionRepository postReactionRepository;
  @Mock private CommentRepository commentRepository;

  @InjectMocks private FeedPostDataMapper mapper;

  private static PostEntity post(Integer id, Integer authorId) {
    PostEntity post = new PostEntity();
    post.setId(id);
    post.setAuthorId(authorId);
    post.setVisibility(PostVisibility.PUBLIC);
    post.setContent("content " + id);
    return post;
  }

  private static UserEntity user(Integer id) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setUsername("author_" + id);
    user.setFullName("Author " + id);
    user.setEliteScore(0);
    return user;
  }

  @Nested
  @DisplayName("toFeedPostData(post)")
  class SinglePost {

    @Test
    @DisplayName("reads the counters from the database rather than assuming zero")
    void readsCounters() {
      // Given
      PostEntity post = post(10, AUTHOR_ID);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID)));
      when(postReactionRepository.countByIdPostId(10)).thenReturn(4L);
      when(commentRepository.countByPostId(10)).thenReturn(2L);

      // When
      FeedPostDataDto data = mapper.toFeedPostData(post);

      // Then
      assertThat(data.getLikeCount()).isEqualTo(4);
      assertThat(data.getCommentCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("carries the author's username, so a feed card can link to their profile")
    void carriesAuthorUsername() {
      // Given — the public profile page is keyed by username and nothing maps an id to one, so
      // without this field the author's name on a card rendered but led nowhere
      PostEntity post = post(10, AUTHOR_ID);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID)));

      // When
      FeedPostDataDto data = mapper.toFeedPostData(post);

      // Then — read off the same UserEntity the other four author fields come from, so it costs
      // no extra query
      assertThat(data.getAuthorUsername()).isEqualTo("author_" + AUTHOR_ID);
      assertThat(data.getAuthorFullName()).isEqualTo("Author " + AUTHOR_ID);
    }

    @Test
    @DisplayName("throws NotFound when the author row is gone")
    void throwsForMissingAuthor() {
      // Given
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> mapper.toFeedPostData(post(10, AUTHOR_ID)))
          .isInstanceOf(NotFoundException.class);
    }
  }

  @Nested
  @DisplayName("toFeedPostDataPage")
  class Page {

    @Test
    @DisplayName("counts the whole page in one query each instead of one per post")
    void batchesCounters() {
      // Given
      when(userRepository.findAllById(List.of(AUTHOR_ID))).thenReturn(List.of(user(AUTHOR_ID)));
      when(postReactionRepository.countByPostIds(List.of(10, 11))).thenReturn(Map.of(10, 3L));
      when(commentRepository.countByPostIds(List.of(10, 11))).thenReturn(Map.of(11, 5L));

      // When
      List<FeedPostDataDto> page =
          mapper.toFeedPostDataPage(List.of(post(10, AUTHOR_ID), post(11, AUTHOR_ID)));

      // Then — posts missing from a count map default to zero, and the per-post queries are unused
      assertThat(page).extracting(FeedPostDataDto::getPostId).containsExactly(10, 11);
      assertThat(page.get(0).getLikeCount()).isEqualTo(3);
      assertThat(page.get(0).getCommentCount()).isZero();
      assertThat(page.get(1).getLikeCount()).isZero();
      assertThat(page.get(1).getCommentCount()).isEqualTo(5);
      verify(postReactionRepository, never()).countByIdPostId(10);
      verify(commentRepository, never()).countByPostId(10);
    }

    @Test
    @DisplayName("skips a post whose author row is gone rather than inventing a placeholder")
    void skipsPostsWithMissingAuthor() {
      // Given: post 11 was written by a deleted account
      when(userRepository.findAllById(List.of(AUTHOR_ID, 2))).thenReturn(List.of(user(AUTHOR_ID)));
      when(postReactionRepository.countByPostIds(List.of(10, 11))).thenReturn(Map.of());
      when(commentRepository.countByPostIds(List.of(10, 11))).thenReturn(Map.of());

      // When
      List<FeedPostDataDto> page =
          mapper.toFeedPostDataPage(List.of(post(10, AUTHOR_ID), post(11, 2)));

      // Then
      assertThat(page).extracting(FeedPostDataDto::getPostId).containsExactly(10);
    }

    @Test
    @DisplayName("returns an empty page without querying anything")
    void shortCircuitsOnEmptyInput() {
      // When
      List<FeedPostDataDto> page = mapper.toFeedPostDataPage(List.of());

      // Then
      assertThat(page).isEmpty();
      verify(postReactionRepository, never()).countByPostIds(anyCollection());
    }
  }

  @Nested
  @DisplayName("updatedAt")
  class EditedAt {

    private FeedPostDataDto mapWithTimestamps(OffsetDateTime createdAt, OffsetDateTime updatedAt) {
      PostEntity post = post(10, AUTHOR_ID);
      post.setCreatedAt(createdAt);
      post.setUpdatedAt(updatedAt);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID)));
      return mapper.toFeedPostData(post);
    }

    @Test
    @DisplayName("stays null for a post nobody has edited")
    void nullForUnedited() {
      // Given — @CreationTimestamp and @UpdateTimestamp are two generators and both fire on the
      // same INSERT, so an untouched post already carries an updated_at a few microseconds later.
      // A straight isAfter() comparison would mark every post in the feed as edited.
      OffsetDateTime created = OffsetDateTime.parse("2026-08-19T10:00:00Z");

      // When
      FeedPostDataDto data = mapWithTimestamps(created, created.plusNanos(400_000));

      // Then
      assertThat(data.getUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("stays null exactly at the one-second threshold (boundary)")
    void nullAtThreshold() {
      // Given — BVA on EDIT_THRESHOLD: the comparison is strictly greater than
      OffsetDateTime created = OffsetDateTime.parse("2026-08-19T10:00:00Z");

      // When
      FeedPostDataDto data = mapWithTimestamps(created, created.plusSeconds(1));

      // Then
      assertThat(data.getUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("carries the timestamp once a real edit has landed (boundary: threshold + 1ms)")
    void setForEdited() {
      // Given — BVA just past the threshold. A real edit is a second request, minutes apart.
      OffsetDateTime created = OffsetDateTime.parse("2026-08-19T10:00:00Z");
      OffsetDateTime edited = created.plusSeconds(1).plusNanos(1_000_000);

      // When
      FeedPostDataDto data = mapWithTimestamps(created, edited);

      // Then — three things point at the body of a post (a skill proof, a stored explanation, the
      // reputation its reactions awarded); an edit that arrives unannounced invalidates all three
      assertThat(data.getUpdatedAt()).isEqualTo(edited);
    }

    @Test
    @DisplayName("stays null for a row written before updated_at was populated")
    void nullWhenColumnIsNull() {
      // Given — EP: legacy row, updated_at NULL
      OffsetDateTime created = OffsetDateTime.parse("2026-08-19T10:00:00Z");

      // When
      FeedPostDataDto data = mapWithTimestamps(created, null);

      // Then
      assertThat(data.getUpdatedAt()).isNull();
    }
  }
}
