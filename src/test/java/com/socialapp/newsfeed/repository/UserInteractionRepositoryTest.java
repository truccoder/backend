package com.socialapp.newsfeed.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.newsfeed.entity.UserInteractionEntity;
import com.socialapp.newsfeed.entity.enums.InteractionType;

/**
 * Component integration tests for {@link UserInteractionRepository} against a real PostgreSQL
 * instance (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own
 * transaction, so no manual cleanup is needed. {@code t_user_interactions} has no foreign key
 * constraints, so arbitrary ids are used directly.
 */
@Transactional
class UserInteractionRepositoryTest extends AbstractIntegrationTest {

  @Autowired private UserInteractionRepository userInteractionRepository;

  private static final Integer USER_ID = 1;
  private static final Integer AUTHOR_A = 10;
  private static final Integer AUTHOR_B = 20;

  private static UserInteractionEntity interaction(
      Integer userId, Integer authorId, InteractionType type) {
    UserInteractionEntity entity = new UserInteractionEntity();
    entity.setUserId(userId);
    entity.setAuthorId(authorId);
    entity.setPostId(1);
    entity.setType(type);
    return entity;
  }

  @Nested
  @DisplayName("countInteractionsByAuthor")
  class CountInteractionsByAuthor {

    @Test
    @DisplayName("groups interaction counts by author")
    void groupsCountsByAuthor() {
      // Given
      userInteractionRepository.saveAndFlush(interaction(USER_ID, AUTHOR_A, InteractionType.LIKE));
      userInteractionRepository.saveAndFlush(
          interaction(USER_ID, AUTHOR_A, InteractionType.COMMENT));
      userInteractionRepository.saveAndFlush(interaction(USER_ID, AUTHOR_B, InteractionType.LIKE));

      // When
      List<AuthorInteractionCount> result =
          userInteractionRepository.countInteractionsByAuthor(
              USER_ID, OffsetDateTime.now().minusDays(1));

      // Then
      Map<Integer, Long> byAuthor =
          result.stream()
              .collect(
                  Collectors.toMap(
                      AuthorInteractionCount::getAuthorId,
                      AuthorInteractionCount::getInteractionCount));
      assertThat(byAuthor).containsEntry(AUTHOR_A, 2L).containsEntry(AUTHOR_B, 1L);
    }

    @Test
    @DisplayName("excludes interactions belonging to a different user")
    void excludesOtherUsersInteractions() {
      // Given
      userInteractionRepository.saveAndFlush(interaction(999, AUTHOR_A, InteractionType.LIKE));

      // When
      List<AuthorInteractionCount> result =
          userInteractionRepository.countInteractionsByAuthor(
              USER_ID, OffsetDateTime.now().minusDays(1));

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName(
        "includes an interaction created strictly after the cutoff (boundary: cutoff - 1s)")
    void includesInteractionJustAfterCutoff() {
      // Given
      UserInteractionEntity saved =
          userInteractionRepository.saveAndFlush(
              interaction(USER_ID, AUTHOR_A, InteractionType.LIKE));
      OffsetDateTime cutoff = saved.getCreatedAt().minusSeconds(1);

      // When
      List<AuthorInteractionCount> result =
          userInteractionRepository.countInteractionsByAuthor(USER_ID, cutoff);

      // Then
      assertThat(result).hasSize(1);
      assertThat(result.get(0).getAuthorId()).isEqualTo(AUTHOR_A);
    }

    @Test
    @DisplayName("excludes an interaction created exactly at the cutoff (boundary: cutoff)")
    void excludesInteractionExactlyAtCutoff() {
      // Given
      UserInteractionEntity saved =
          userInteractionRepository.saveAndFlush(
              interaction(USER_ID, AUTHOR_A, InteractionType.LIKE));
      OffsetDateTime cutoff = saved.getCreatedAt();

      // When
      List<AuthorInteractionCount> result =
          userInteractionRepository.countInteractionsByAuthor(USER_ID, cutoff);

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("excludes an interaction created before the cutoff (boundary: cutoff + 1s)")
    void excludesInteractionBeforeCutoff() {
      // Given
      UserInteractionEntity saved =
          userInteractionRepository.saveAndFlush(
              interaction(USER_ID, AUTHOR_A, InteractionType.LIKE));
      OffsetDateTime cutoff = saved.getCreatedAt().plusSeconds(1);

      // When
      List<AuthorInteractionCount> result =
          userInteractionRepository.countInteractionsByAuthor(USER_ID, cutoff);

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns an empty list when the user has no interactions")
    void returnsEmptyListWhenNoInteractions() {
      // When
      List<AuthorInteractionCount> result =
          userInteractionRepository.countInteractionsByAuthor(
              USER_ID, OffsetDateTime.now().minusDays(1));

      // Then
      assertThat(result).isEmpty();
    }
  }
}
