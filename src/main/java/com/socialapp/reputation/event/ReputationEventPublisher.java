package com.socialapp.reputation.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.socialapp.reputation.entity.enums.RepSourceType;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ReputationEventPublisher {
  private final ApplicationEventPublisher eventPublisher;

  public void award(Integer userId, RepSourceType sourceType, String sourceId) {
    eventPublisher.publishEvent(
        ReputationAwardEvent.builder()
            .userId(userId)
            .sourceType(sourceType)
            .sourceId(sourceId)
            .revoke(false)
            .build());
  }

  public void revoke(Integer userId, RepSourceType sourceType, String sourceId) {
    eventPublisher.publishEvent(
        ReputationAwardEvent.builder()
            .userId(userId)
            .sourceType(sourceType)
            .sourceId(sourceId)
            .revoke(true)
            .build());
  }

  /**
   * Bulk-revokes every {@code sourceType} event whose {@code sourceId} starts with {@code
   * sourceIdPrefix}, and rebuilds {@code recipientId}'s score afterwards. For content deletion,
   * where the events cannot be enumerated as individual triples — a deleted post's per-reactor
   * {@code REACTION_RECEIVED} rows, say.
   */
  public void revokeByPrefix(Integer recipientId, RepSourceType sourceType, String sourceIdPrefix) {
    eventPublisher.publishEvent(
        ReputationAwardEvent.builder()
            .userId(recipientId)
            .sourceType(sourceType)
            .sourceIdPrefix(sourceIdPrefix)
            .revoke(true)
            .build());
  }
}
