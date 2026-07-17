package com.socialapp.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.github.entity.GithubStatsEntity;
import com.socialapp.github.repository.GithubStatsRepository;
import com.socialapp.security.entity.UserEntity;

/**
 * Component (unit) tests for {@link GithubSyncScheduler}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing; Section 4.5.3 Error Guessing — one user's sync failure must not stop the
 * batch from processing the rest).
 */
@ExtendWith(MockitoExtension.class)
class GithubSyncSchedulerTest {

  @Mock private GithubStatsRepository githubStatsRepository;
  @Mock private GithubService githubService;

  @InjectMocks private GithubSyncScheduler githubSyncScheduler;

  private static GithubStatsEntity entity(Integer userId) {
    UserEntity user = new UserEntity();
    user.setId(userId);
    return GithubStatsEntity.builder().user(user).githubUsername("user" + userId).build();
  }

  @Nested
  @DisplayName("syncGithubProfiles")
  class SyncGithubProfilesTests {

    @Test
    @DisplayName("should do nothing when no users are due for sync")
    void shouldDoNothing_whenNoUsersDue() {
      // Given
      when(githubStatsRepository.findUsersToSync(any(), any())).thenReturn(List.of());

      // When
      githubSyncScheduler.syncGithubProfiles();

      // Then
      verify(githubService, never()).syncGithubData(any());
    }

    @Test
    @DisplayName("should sync every user that is due")
    void shouldSyncEveryDueUser() {
      // Given
      GithubStatsEntity user1 = entity(1);
      GithubStatsEntity user2 = entity(2);
      when(githubStatsRepository.findUsersToSync(any(), any())).thenReturn(List.of(user1, user2));

      // When
      githubSyncScheduler.syncGithubProfiles();

      // Then
      verify(githubService).syncGithubData(user1);
      verify(githubService).syncGithubData(user2);
    }

    @Test
    @DisplayName("should continue syncing the rest of the batch after one user's sync fails")
    void shouldContinueBatch_whenOneUserSyncFails() {
      // Given
      GithubStatsEntity failingUser = entity(1);
      GithubStatsEntity nextUser = entity(2);
      when(githubStatsRepository.findUsersToSync(any(), any()))
          .thenReturn(List.of(failingUser, nextUser));
      doThrow(new RuntimeException("GitHub API down"))
          .when(githubService)
          .syncGithubData(failingUser);

      // When
      githubSyncScheduler.syncGithubProfiles(); // must not propagate the failure

      // Then
      verify(githubService).syncGithubData(nextUser);
    }

    @Test
    @DisplayName("should stop the batch and restore the interrupt flag when interrupted")
    void shouldStopBatch_whenInterrupted() {
      // Given: pre-flagging this thread as interrupted makes the loop's Thread.sleep(500) throw
      // InterruptedException immediately after the first user, with no real waiting involved.
      GithubStatsEntity user1 = entity(1);
      GithubStatsEntity user2 = entity(2);
      when(githubStatsRepository.findUsersToSync(any(), any())).thenReturn(List.of(user1, user2));
      Thread.currentThread().interrupt();

      try {
        // When
        githubSyncScheduler.syncGithubProfiles();

        // Then: breaks out before the second user, and restores the interrupt flag
        verify(githubService).syncGithubData(user1);
        verify(githubService, never()).syncGithubData(user2);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
      } finally {
        Thread.interrupted(); // clear the flag so it doesn't leak into later tests
      }
    }
  }
}
