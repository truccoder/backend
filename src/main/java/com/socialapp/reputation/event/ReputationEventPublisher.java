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
}
