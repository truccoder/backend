package com.socialapp.posts.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.posts.entity.EventDetails;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link PostRepository} against a real PostgreSQL instance
 * (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own
 * transaction, so no manual cleanup is needed. {@code t_posts.author_id} references a real user,
 * so each test seeds those via {@link UserRepository}.
 */
@Transactional
class PostRepositoryTest extends AbstractIntegrationTest {

  @Autowired private PostRepository postRepository;
  @Autowired private UserRepository userRepository;

  private Integer authorId;

  @BeforeEach
  void seedAuthor() {
    authorId = userRepository.saveAndFlush(user("author@example.com", "author")).getId();
  }

  private static UserEntity user(String email, String username) {
    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPassword("hashed-password");
    user.setUsername(username);
    user.setFullName("Test User");
    return user;
  }

  private static PostEntity post(
      Integer authorId, String content, PostVisibility visibility, ModerationStatus status) {
    PostEntity post = new PostEntity();
    post.setAuthorId(authorId);
    post.setContent(content);
    post.setVisibility(visibility);
    post.setModerationStatus(status);
    return post;
  }

  @Nested
  @DisplayName("findByModerationStatus")
  class FindByModerationStatus {

    @Test
    @DisplayName("returns posts matching the given status")
    void returnsMatchingPosts() {
      // Given
      PostEntity approved =
          postRepository.saveAndFlush(
              post(authorId, "approved post", PostVisibility.PUBLIC, ModerationStatus.APPROVED));
      postRepository.saveAndFlush(
          post(authorId, "pending post", PostVisibility.PUBLIC, ModerationStatus.PENDING_REVIEW));

      // When
      List<PostEntity> result = postRepository.findByModerationStatus(ModerationStatus.APPROVED);

      // Then
      assertThat(result).extracting(PostEntity::getId).containsExactly(approved.getId());
    }

    @Test
    @DisplayName("returns an empty list when no post matches the status")
    void returnsEmptyListWhenNoMatch() {
      // When
      List<PostEntity> result = postRepository.findByModerationStatus(ModerationStatus.REJECTED);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("findByAuthorIdAndModerationStatus")
  class FindByAuthorIdAndModerationStatus {

    @Test
    @DisplayName("returns posts matching both author and status")
    void returnsMatchingPosts() {
      // Given
      Integer otherAuthorId =
          userRepository.saveAndFlush(user("other@example.com", "other")).getId();
      PostEntity mine =
          postRepository.saveAndFlush(
              post(authorId, "mine", PostVisibility.PUBLIC, ModerationStatus.APPROVED));
      postRepository.saveAndFlush(
          post(otherAuthorId, "not mine", PostVisibility.PUBLIC, ModerationStatus.APPROVED));

      // When
      List<PostEntity> result =
          postRepository.findByAuthorIdAndModerationStatus(authorId, ModerationStatus.APPROVED);

      // Then
      assertThat(result).extracting(PostEntity::getId).containsExactly(mine.getId());
    }

    @Test
    @DisplayName("returns an empty list when the author has no post with that status")
    void returnsEmptyListWhenNoMatch() {
      // Given
      postRepository.saveAndFlush(
          post(authorId, "mine", PostVisibility.PUBLIC, ModerationStatus.PENDING_REVIEW));

      // When
      List<PostEntity> result =
          postRepository.findByAuthorIdAndModerationStatus(authorId, ModerationStatus.APPROVED);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("search")
  class Search {

    @Test
    @DisplayName("returns every post when all filters are null")
    void returnsEveryPostWhenFiltersAreNull() {
      // Given
      PostEntity post =
          postRepository.saveAndFlush(
              post(authorId, "content", PostVisibility.PUBLIC, ModerationStatus.APPROVED));

      // When
      Page<PostEntity> result = postRepository.search(null, null, null, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).extracting(PostEntity::getId).contains(post.getId());
    }

    @Test
    @DisplayName("filters by postId only")
    void filtersByPostIdOnly() {
      // Given
      PostEntity target =
          postRepository.saveAndFlush(
              post(authorId, "target", PostVisibility.PUBLIC, ModerationStatus.APPROVED));
      postRepository.saveAndFlush(
          post(authorId, "other", PostVisibility.PUBLIC, ModerationStatus.APPROVED));

      // When
      Page<PostEntity> result =
          postRepository.search(target.getId(), null, null, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).extracting(PostEntity::getId).containsExactly(target.getId());
    }

    @Test
    @DisplayName("filters by authorId only")
    void filtersByAuthorIdOnly() {
      // Given
      Integer otherAuthorId =
          userRepository.saveAndFlush(user("other@example.com", "other")).getId();
      PostEntity mine =
          postRepository.saveAndFlush(
              post(authorId, "mine", PostVisibility.PUBLIC, ModerationStatus.APPROVED));
      postRepository.saveAndFlush(
          post(otherAuthorId, "not mine", PostVisibility.PUBLIC, ModerationStatus.APPROVED));

      // When
      Page<PostEntity> result = postRepository.search(null, authorId, null, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).extracting(PostEntity::getId).containsExactly(mine.getId());
    }

    @Test
    @DisplayName("filters by status only")
    void filtersByStatusOnly() {
      // Given
      PostEntity pending =
          postRepository.saveAndFlush(
              post(authorId, "pending", PostVisibility.PUBLIC, ModerationStatus.PENDING_REVIEW));
      postRepository.saveAndFlush(
          post(authorId, "approved", PostVisibility.PUBLIC, ModerationStatus.APPROVED));

      // When
      Page<PostEntity> result =
          postRepository.search(
              null, authorId, ModerationStatus.PENDING_REVIEW, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent())
          .extracting(PostEntity::getId)
          .containsExactly(pending.getId());
    }

    @Test
    @DisplayName("returns an empty page when no post matches all filters")
    void returnsEmptyPageWhenNoMatch() {
      // Given
      postRepository.saveAndFlush(
          post(authorId, "approved", PostVisibility.PUBLIC, ModerationStatus.APPROVED));

      // When
      Page<PostEntity> result =
          postRepository.search(null, authorId, ModerationStatus.REJECTED, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).isEmpty();
    }
  }

  @Nested
  @DisplayName("searchByContentOrEventName")
  class SearchByContentOrEventName {

    @Test
    @DisplayName("matches post content ignoring accents and case")
    void matchesContentIgnoringAccentsAndCase() {
      // Given
      PostEntity target =
          postRepository.saveAndFlush(
              post(
                  authorId,
                  "Món Phở ngon tuyệt vời",
                  PostVisibility.PUBLIC,
                  ModerationStatus.APPROVED));

      // When
      Page<PostEntity> result =
          postRepository.searchByContentOrEventName(
              "pho ngon", authorId, List.of(), PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).extracting(PostEntity::getId).containsExactly(target.getId());
    }

    @Test
    @DisplayName("matches the event title stored in event_details")
    void matchesEventTitle() {
      // Given
      PostEntity target =
          post(authorId, "join us", PostVisibility.PUBLIC, ModerationStatus.APPROVED);
      target.setPostType(com.socialapp.posts.entity.enums.PostType.EVENT);
      target.setEventDetails(
          new EventDetails("Distinctwordconf 2026", null, null, null, null, null, null, null));
      postRepository.saveAndFlush(target);

      // When
      Page<PostEntity> result =
          postRepository.searchByContentOrEventName(
              "distinctwordconf", authorId, List.of(), PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).extracting(PostEntity::getId).containsExactly(target.getId());
    }

    @Test
    @DisplayName("a PUBLIC post is visible to a stranger")
    void publicPostVisibleToStranger() {
      // Given
      Integer strangerId =
          userRepository.saveAndFlush(user("stranger@example.com", "stranger")).getId();
      PostEntity target =
          postRepository.saveAndFlush(
              post(
                  authorId,
                  "uniquesearchtermpublic",
                  PostVisibility.PUBLIC,
                  ModerationStatus.APPROVED));

      // When
      Page<PostEntity> result =
          postRepository.searchByContentOrEventName(
              "uniquesearchtermpublic", strangerId, List.of(), PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).extracting(PostEntity::getId).containsExactly(target.getId());
    }

    @Test
    @DisplayName("a PRIVATE post is not visible to a stranger")
    void privatePostHiddenFromStranger() {
      // Given
      Integer strangerId =
          userRepository.saveAndFlush(user("stranger@example.com", "stranger")).getId();
      postRepository.saveAndFlush(
          post(
              authorId,
              "uniquesearchtermprivate",
              PostVisibility.PRIVATE,
              ModerationStatus.APPROVED));

      // When
      Page<PostEntity> result =
          postRepository.searchByContentOrEventName(
              "uniquesearchtermprivate", strangerId, List.of(), PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("a PRIVATE post is visible to its own author")
    void privatePostVisibleToAuthor() {
      // Given
      PostEntity target =
          postRepository.saveAndFlush(
              post(
                  authorId,
                  "uniquesearchtermprivate2",
                  PostVisibility.PRIVATE,
                  ModerationStatus.APPROVED));

      // When
      Page<PostEntity> result =
          postRepository.searchByContentOrEventName(
              "uniquesearchtermprivate2", authorId, List.of(), PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).extracting(PostEntity::getId).containsExactly(target.getId());
    }

    @Test
    @DisplayName("a FRIENDS post is visible to a friend but not to a stranger")
    void friendsPostVisibleOnlyToFriend() {
      // Given
      Integer friendId = userRepository.saveAndFlush(user("friend@example.com", "friend")).getId();
      Integer strangerId =
          userRepository.saveAndFlush(user("stranger@example.com", "stranger")).getId();
      PostEntity target =
          postRepository.saveAndFlush(
              post(
                  authorId,
                  "uniquesearchtermfriends",
                  PostVisibility.FRIENDS,
                  ModerationStatus.APPROVED));

      // When
      Page<PostEntity> friendResult =
          postRepository.searchByContentOrEventName(
              "uniquesearchtermfriends", friendId, List.of(authorId), PageRequest.of(0, 10));
      Page<PostEntity> strangerResult =
          postRepository.searchByContentOrEventName(
              "uniquesearchtermfriends", strangerId, List.of(), PageRequest.of(0, 10));

      // Then
      assertThat(friendResult.getContent())
          .extracting(PostEntity::getId)
          .containsExactly(target.getId());
      assertThat(strangerResult.getContent()).isEmpty();
    }

    @Test
    @DisplayName("returns an empty page when nothing matches")
    void returnsEmptyPageWhenNoMatch() {
      // Given
      postRepository.saveAndFlush(
          post(authorId, "some content", PostVisibility.PUBLIC, ModerationStatus.APPROVED));

      // When
      Page<PostEntity> result =
          postRepository.searchByContentOrEventName(
              "zzz_no_such_keyword", authorId, List.of(), PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).isEmpty();
    }
  }
}
