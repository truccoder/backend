package com.socialapp.github.service;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.socialapp.github.entity.GithubStatsEntity;
import com.socialapp.github.repository.GithubStatsRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class GithubSyncScheduler {

  private final GithubStatsRepository githubStatsRepository;
  private final GithubService githubService;

  // Run every minute (60000 ms) after the previous execution completes
  @Scheduled(fixedDelay = 60000)
  public void syncGithubProfiles() {
    log.debug("Starting background GitHub sync job...");

    // Sync users whose last_synced_at is older than 24 hours (or null)
    OffsetDateTime threshold = OffsetDateTime.now().minusHours(24);

    // Fetch up to 15 users per minute (15 * 60 = 900 users/hour, well below 5000 API limit)
    List<GithubStatsEntity> usersToSync =
        githubStatsRepository.findUsersToSync(threshold, PageRequest.of(0, 15));

    if (usersToSync.isEmpty()) {
      log.debug("No users require GitHub sync at this time.");
      return;
    }

    log.info("Found {} users to sync GitHub stats.", usersToSync.size());

    for (GithubStatsEntity entity : usersToSync) {
      try {
        githubService.syncGithubData(entity);
        // Sleep slightly to avoid spamming GitHub APIs too fast
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.error("Sync thread interrupted", e);
        break;
      } catch (Exception e) {
        log.error("Failed to sync stats for user ID {}", entity.getUser().getId(), e);
      }
    }

    log.debug("Completed background GitHub sync job.");
  }
}
