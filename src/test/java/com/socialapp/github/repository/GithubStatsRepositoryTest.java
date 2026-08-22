package com.socialapp.github.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.github.entity.GithubStatsEntity;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link GithubStatsRepository} against a real PostgreSQL instance
 * (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own transaction,
 * so no manual cleanup is needed.
 *
 * <p>The interesting method is {@code findUsersToSync}, which feeds {@code GithubSyncScheduler}
 * every 60 seconds. A row with no access token can never sync — {@code GithubService#performSync}
 * throws on it — so if the query returned it, the scheduler would spend one of its 15 slots per
 * minute logging the same error forever, and the row would still be stale on the next run. That is
 * exactly what the seeded rows (access_token NULL) did once their last_synced_at aged past 24
 * hours.
 */
@Transactional
class GithubStatsRepositoryTest extends AbstractIntegrationTest {

  @Autowired private GithubStatsRepository githubStatsRepository;
  @Autowired private UserRepository userRepository;

  private OffsetDateTime threshold;

  @BeforeEach
  void setUp() {
    threshold = OffsetDateTime.now().minusHours(24);
  }

  private UserEntity user(String username) {
    UserEntity user = new UserEntity();
    user.setEmail(username + "@example.com");
    user.setPassword("hashed-password");
    user.setUsername(username);
    user.setFullName("Test User");
    return userRepository.saveAndFlush(user);
  }

  private GithubStatsEntity stats(String username, String accessToken, OffsetDateTime lastSynced) {
    return githubStatsRepository.saveAndFlush(
        GithubStatsEntity.builder()
            .user(user(username))
            .githubUsername(username)
            .accessToken(accessToken)
            .lastSyncedAt(lastSynced)
            .build());
  }

  private List<String> dueUsernames() {
    return githubStatsRepository.findUsersToSync(threshold, PageRequest.of(0, 50)).stream()
        .map(GithubStatsEntity::getGithubUsername)
        .toList();
  }

  @Nested
  @DisplayName("findUsersToSync")
  class FindUsersToSync {

    @Test
    @DisplayName("returns rows whose last sync is older than the threshold")
    void returnsStaleRows() {
      // Given
      stats("gh-stale", "token", OffsetDateTime.now().minusDays(3));

      // When / Then
      assertThat(dueUsernames()).contains("gh-stale");
    }

    @Test
    @DisplayName("returns rows that have never been synced")
    void returnsNeverSyncedRows() {
      // Given
      stats("gh-never-synced", "token", null);

      // When / Then
      assertThat(dueUsernames()).contains("gh-never-synced");
    }

    @Test
    @DisplayName("skips rows synced within the threshold")
    void skipsFreshRows() {
      // Given
      stats("gh-fresh", "token", OffsetDateTime.now().minusHours(1));

      // When / Then
      assertThat(dueUsernames()).doesNotContain("gh-fresh");
    }

    @Test
    @DisplayName("skips rows with no access token, however stale they are")
    void skipsRowsWithoutAccessToken() {
      // Given: the shape the dev seed writes — linked, displayable, but unsyncable
      stats("gh-seeded-no-token", null, OffsetDateTime.now().minusDays(30));
      stats("gh-no-token-never-synced", null, null);

      // When / Then
      assertThat(dueUsernames()).doesNotContain("gh-seeded-no-token", "gh-no-token-never-synced");
    }

    @Test
    @DisplayName("honours the page size so one run cannot exceed the API budget")
    void honoursPageSize() {
      // Given
      stats("gh-batch-1", "token", OffsetDateTime.now().minusDays(3));
      stats("gh-batch-2", "token", OffsetDateTime.now().minusDays(2));

      // When
      List<GithubStatsEntity> firstPage =
          githubStatsRepository.findUsersToSync(threshold, PageRequest.of(0, 1));

      // Then
      assertThat(firstPage).hasSize(1);
    }
  }
}
