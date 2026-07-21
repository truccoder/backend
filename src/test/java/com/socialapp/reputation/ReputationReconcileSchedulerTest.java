package com.socialapp.reputation;

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

import com.socialapp.reputation.repository.ReputationEventRepository;
import com.socialapp.reputation.service.ReputationService;

/**
 * Component (unit) tests for {@link ReputationReconcileScheduler}, per ISTQB CTFL v4.0.1 (Section
 * 2.2.1 component testing; Section 4.5.3 Error Guessing — one user's reconcile failure must not
 * stop the batch from processing the rest, mirroring {@code GithubSyncSchedulerTest}).
 */
@ExtendWith(MockitoExtension.class)
class ReputationReconcileSchedulerTest {

  @Mock private ReputationEventRepository reputationEventRepository;
  @Mock private ReputationService reputationService;

  @InjectMocks private ReputationReconcileScheduler reputationReconcileScheduler;

  @Nested
  @DisplayName("reconcileEliteScores")
  class ReconcileEliteScoresTests {

    @Test
    @DisplayName("should do nothing when no user has any reputation event")
    void shouldDoNothing_whenNoUsersHaveEvents() {
      // Given
      when(reputationEventRepository.findDistinctUserIds()).thenReturn(List.of());

      // When
      reputationReconcileScheduler.reconcileEliteScores();

      // Then
      verify(reputationService, never()).reconcile(any());
    }

    @Test
    @DisplayName("should reconcile every user that has at least one reputation event")
    void shouldReconcileEveryUserWithEvents() {
      // Given
      when(reputationEventRepository.findDistinctUserIds()).thenReturn(List.of(1, 2));

      // When
      reputationReconcileScheduler.reconcileEliteScores();

      // Then
      verify(reputationService).reconcile(1);
      verify(reputationService).reconcile(2);
    }

    @Test
    @DisplayName(
        "should continue reconciling the rest of the batch after one user's reconcile fails")
    void shouldContinueBatch_whenOneUserReconcileFails() {
      // Given
      when(reputationEventRepository.findDistinctUserIds()).thenReturn(List.of(1, 2));
      doThrow(new RuntimeException("DB unavailable")).when(reputationService).reconcile(1);

      // When
      reputationReconcileScheduler.reconcileEliteScores(); // must not propagate the failure

      // Then
      verify(reputationService).reconcile(2);
    }
  }
}
