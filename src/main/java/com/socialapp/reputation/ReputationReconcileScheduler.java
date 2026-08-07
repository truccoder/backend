package com.socialapp.reputation;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.socialapp.reputation.repository.ReputationEventRepository;
import com.socialapp.reputation.service.ReputationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Nightly insurance against {@code t_users.elite_score} ever drifting from the ledger it's
 * denormalized from — recomputes each user's score as {@code SUM(points)} over their reputation
 * events. The award/revoke path keeps the two in sync on every write, so this should normally be
 * a no-op; it exists for the same reason {@code GithubSyncScheduler} exists — a cheap correcting
 * pass in case something in between missed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReputationReconcileScheduler {
  private final ReputationEventRepository reputationEventRepository;
  private final ReputationService reputationService;

  @Scheduled(cron = "0 0 3 * * *")
  public void reconcileEliteScores() {
    List<Integer> userIds = reputationEventRepository.findDistinctUserIds();
    log.info("Starting nightly reputation reconcile for {} users", userIds.size());

    for (Integer userId : userIds) {
      try {
        reputationService.reconcile(userId);
      } catch (Exception e) {
        log.error("Failed to reconcile reputation for user {}", userId, e);
      }
    }

    log.info("Finished nightly reputation reconcile");
  }
}
