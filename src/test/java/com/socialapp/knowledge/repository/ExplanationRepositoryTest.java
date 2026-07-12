package com.socialapp.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.knowledge.entity.ExplanationEntity;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link ExplanationRepository} against a real PostgreSQL
 * instance (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own
 * transaction, so no manual cleanup is needed. {@code t_explanations} has foreign keys to real
 * users and posts, so each test seeds those via {@link UserRepository}/{@link PostRepository}
 * rather than using arbitrary ids.
 */
@Transactional
class ExplanationRepositoryTest extends AbstractIntegrationTest {

  @Autowired private ExplanationRepository explanationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private PostRepository postRepository;

  private Integer userId;
  private Integer postId;

  @BeforeEach
  void seedUserAndPost() {
    userId = userRepository.saveAndFlush(user("owner@example.com", "owner")).getId();
    postId = postRepository.saveAndFlush(post(userId)).getId();
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

  private ExplanationEntity explanation(Integer postId, Integer userId, Integer version) {
    return ExplanationEntity.builder()
        .postId(postId)
        .userId(userId)
        .originalContent("original")
        .explanationContent("explanation v" + version)
        .version(version)
        .build();
  }

  @Nested
  @DisplayName("findLatestByPostAndUser")
  class FindLatestByPostAndUser {

    @Test
    @DisplayName("returns the highest version among several")
    void returnsHighestVersion() {
      // Given
      explanationRepository.saveAndFlush(explanation(postId, userId, 1));
      explanationRepository.saveAndFlush(explanation(postId, userId, 3));
      explanationRepository.saveAndFlush(explanation(postId, userId, 2));

      // When
      Optional<ExplanationEntity> result =
          explanationRepository.findLatestByPostAndUser(postId, userId);

      // Then
      assertThat(result).isPresent();
      assertThat(result.get().getVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("returns empty when no explanation exists for the post/user pair")
    void returnsEmptyWhenNoneExists() {
      // When
      Optional<ExplanationEntity> result =
          explanationRepository.findLatestByPostAndUser(postId, userId);

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("does not match explanations belonging to a different user")
    void ignoresOtherUsersExplanations() {
      // Given
      Integer otherUserId = userRepository.saveAndFlush(user("other@example.com", "other")).getId();
      explanationRepository.saveAndFlush(explanation(postId, otherUserId, 5));

      // When
      Optional<ExplanationEntity> result =
          explanationRepository.findLatestByPostAndUser(postId, userId);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("findByUserIdOrderByCreatedAtDesc")
  class FindByUserIdOrderByCreatedAtDesc {

    @Test
    @DisplayName("orders results newest first")
    void ordersNewestFirst() {
      // Given
      Integer secondPostId = postRepository.saveAndFlush(post(userId)).getId();
      ExplanationEntity first = explanationRepository.saveAndFlush(explanation(postId, userId, 1));
      ExplanationEntity second =
          explanationRepository.saveAndFlush(explanation(secondPostId, userId, 1));

      // When
      List<ExplanationEntity> result =
          explanationRepository.findByUserIdOrderByCreatedAtDesc(userId);

      // Then
      assertThat(result)
          .extracting(ExplanationEntity::getId)
          .containsExactly(second.getId(), first.getId());
    }

    @Test
    @DisplayName("returns an empty list when the user has no explanations")
    void returnsEmptyListWhenNoneExist() {
      // When
      List<ExplanationEntity> result =
          explanationRepository.findByUserIdOrderByCreatedAtDesc(userId);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("findByUserIdUpdatedAfter")
  class FindByUserIdUpdatedAfter {

    @Test
    @DisplayName(
        "includes an explanation updated strictly after the cutoff (boundary: cutoff - 1s)")
    void includesExplanationJustAfterCutoff() {
      // Given
      ExplanationEntity saved = explanationRepository.saveAndFlush(explanation(postId, userId, 1));
      OffsetDateTime cutoff = saved.getUpdatedAt().minusSeconds(1);

      // When
      List<ExplanationEntity> result =
          explanationRepository.findByUserIdUpdatedAfter(userId, cutoff);

      // Then
      assertThat(result).extracting(ExplanationEntity::getId).containsExactly(saved.getId());
    }

    @Test
    @DisplayName("excludes an explanation updated exactly at the cutoff (boundary: cutoff)")
    void excludesExplanationExactlyAtCutoff() {
      // Given
      ExplanationEntity saved = explanationRepository.saveAndFlush(explanation(postId, userId, 1));
      OffsetDateTime cutoff = saved.getUpdatedAt();

      // When
      List<ExplanationEntity> result =
          explanationRepository.findByUserIdUpdatedAfter(userId, cutoff);

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("excludes an explanation updated before the cutoff (boundary: cutoff + 1s)")
    void excludesExplanationBeforeCutoff() {
      // Given
      ExplanationEntity saved = explanationRepository.saveAndFlush(explanation(postId, userId, 1));
      OffsetDateTime cutoff = saved.getUpdatedAt().plusSeconds(1);

      // When
      List<ExplanationEntity> result =
          explanationRepository.findByUserIdUpdatedAfter(userId, cutoff);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("findMaxVersion")
  class FindMaxVersion {

    @Test
    @DisplayName("returns the highest version for the post/user pair")
    void returnsHighestVersion() {
      // Given
      explanationRepository.saveAndFlush(explanation(postId, userId, 1));
      explanationRepository.saveAndFlush(explanation(postId, userId, 4));

      // When
      Optional<Integer> result = explanationRepository.findMaxVersion(postId, userId);

      // Then
      assertThat(result).contains(4);
    }

    @Test
    @DisplayName("returns empty when no explanation exists for the post/user pair")
    void returnsEmptyWhenNoneExists() {
      // When
      Optional<Integer> result = explanationRepository.findMaxVersion(postId, userId);

      // Then
      assertThat(result).isEmpty();
    }
  }
}
