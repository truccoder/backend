package com.socialapp.reputation.event;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.socialapp.reputation.service.ReputationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Awards/revokes reputation only after the triggering transaction actually commits — mirrors
 * {@code ModerationEventListener}'s AFTER_COMMIT + REQUIRES_NEW shape, so a rolled-back reaction
 * or verification never leaves a stray reputation event behind.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReputationAwardListener {
  private final ReputationService reputationService;

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void handleReputationEvent(ReputationAwardEvent event) {
    try {
      if (event.isRevoke()) {
        reputationService.revoke(event.getUserId(), event.getSourceType(), event.getSourceId());
      } else {
        reputationService.award(event.getUserId(), event.getSourceType(), event.getSourceId());
      }
    } catch (Exception e) {
      log.error(
          "Failed to process reputation event: user={} source={} id={} revoke={}",
          event.getUserId(),
          event.getSourceType(),
          event.getSourceId(),
          event.isRevoke(),
          e);
    }
  }
}
