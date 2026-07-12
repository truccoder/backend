package com.socialapp.moderation.repository;

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
import com.socialapp.moderation.entity.ModerationLogEntity;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link ModerationLogRepository} against a real PostgreSQL
 * instance (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own
 * transaction, so no manual cleanup is needed. {@code t_moderation_logs.post_id} references a
 * real post, so each test seeds one first. Flyway also seeds two PENDING_REVIEW logs (see
 * V21__seed_pending_review_posts.sql), so assertions scope to the post created by each test
 * rather than assuming an empty table.
 */
@Transactional
class ModerationLogRepositoryTest extends AbstractIntegrationTest {

  @Autowired private ModerationLogRepository moderationLogRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private PostRepository postRepository;

  private Integer authorId;
  private Integer postId;

  @BeforeEach
  void seedAuthorAndPost() {
    authorId = userRepository.saveAndFlush(user("author@example.com", "author")).getId();
    postId = postRepository.saveAndFlush(post(authorId)).getId();
  }

  private static UserEntity user(String email, String username) {
    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPassword("hashed-password");
    user.setUsername(username);
    user.setFullName("Test User");
    return user;
  }

  private static PostEntity post(Integer authorId) {
    PostEntity post = new PostEntity();
    post.setContent("post content");
    post.setAuthorId(authorId);
    return post;
  }

  private static ModerationLogEntity log(Integer postId, ModerationStatus status) {
    return ModerationLogEntity.builder().postId(postId).status(status).build();
  }

  @Nested
  @DisplayName("findByPostId")
  class FindByPostId {

    @Test
    @DisplayName("returns every log entry for the post")
    void returnsLogsForPost() {
      // Given
      moderationLogRepository.saveAndFlush(log(postId, ModerationStatus.PENDING_MODERATION));
      moderationLogRepository.saveAndFlush(log(postId, ModerationStatus.APPROVED));

      // When
      List<ModerationLogEntity> result = moderationLogRepository.findByPostId(postId);

      // Then
      assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("returns an empty list when the post has no log entries")
    void returnsEmptyListWhenNoLogs() {
      // When
      List<ModerationLogEntity> result = moderationLogRepository.findByPostId(postId);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("findByPostIdOrderByCreatedAtAsc")
  class FindByPostIdOrderByCreatedAtAsc {

    @Test
    @DisplayName("orders the post's log entries oldest first")
    void ordersOldestFirst() {
      // Given
      ModerationLogEntity first =
          moderationLogRepository.saveAndFlush(log(postId, ModerationStatus.PENDING_MODERATION));
      ModerationLogEntity second =
          moderationLogRepository.saveAndFlush(log(postId, ModerationStatus.APPROVED));

      // When
      List<ModerationLogEntity> result =
          moderationLogRepository.findByPostIdOrderByCreatedAtAsc(postId);

      // Then
      assertThat(result)
          .extracting(ModerationLogEntity::getId)
          .containsExactly(first.getId(), second.getId());
    }
  }

  @Nested
  @DisplayName("findByStatus")
  class FindByStatus {

    @Test
    @DisplayName("returns log entries matching the given status")
    void returnsMatchingLogs() {
      // Given
      ModerationLogEntity approved =
          moderationLogRepository.saveAndFlush(log(postId, ModerationStatus.APPROVED));
      moderationLogRepository.saveAndFlush(log(postId, ModerationStatus.REJECTED));

      // When
      List<ModerationLogEntity> result =
          moderationLogRepository.findByStatus(ModerationStatus.APPROVED);

      // Then
      assertThat(result).extracting(ModerationLogEntity::getId).contains(approved.getId());
    }
  }

  @Nested
  @DisplayName("search")
  class Search {

    @Test
    @DisplayName("filters by postId only")
    void filtersByPostIdOnly() {
      // Given
      ModerationLogEntity target =
          moderationLogRepository.saveAndFlush(log(postId, ModerationStatus.PENDING_MODERATION));

      // When
      Page<ModerationLogEntity> result =
          moderationLogRepository.search(postId, null, null, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent())
          .extracting(ModerationLogEntity::getId)
          .containsExactly(target.getId());
    }

    @Test
    @DisplayName("filters by status scoped to a known post")
    void filtersByStatusScopedToPost() {
      // Given
      ModerationLogEntity approved =
          moderationLogRepository.saveAndFlush(log(postId, ModerationStatus.APPROVED));
      moderationLogRepository.saveAndFlush(log(postId, ModerationStatus.REJECTED));

      // When
      Page<ModerationLogEntity> result =
          moderationLogRepository.search(
              postId, null, ModerationStatus.APPROVED, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent())
          .extracting(ModerationLogEntity::getId)
          .containsExactly(approved.getId());
    }

    @Test
    @DisplayName("filters by authorId via the post's author")
    void filtersByAuthorId() {
      // Given
      Integer otherAuthorId =
          userRepository.saveAndFlush(user("other@example.com", "other")).getId();
      Integer otherPostId = postRepository.saveAndFlush(post(otherAuthorId)).getId();
      ModerationLogEntity mine =
          moderationLogRepository.saveAndFlush(log(postId, ModerationStatus.PENDING_MODERATION));
      moderationLogRepository.saveAndFlush(log(otherPostId, ModerationStatus.PENDING_MODERATION));

      // When
      Page<ModerationLogEntity> result =
          moderationLogRepository.search(null, authorId, null, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent())
          .extracting(ModerationLogEntity::getId)
          .containsExactly(mine.getId());
    }

    @Test
    @DisplayName("returns an empty page when no log matches all filters")
    void returnsEmptyPageWhenNoMatch() {
      // Given
      moderationLogRepository.saveAndFlush(log(postId, ModerationStatus.APPROVED));

      // When
      Page<ModerationLogEntity> result =
          moderationLogRepository.search(
              postId, null, ModerationStatus.REJECTED, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).isEmpty();
    }
  }
}
