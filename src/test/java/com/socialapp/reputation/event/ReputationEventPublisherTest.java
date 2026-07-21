package com.socialapp.reputation.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.socialapp.reputation.entity.enums.RepSourceType;

/**
 * Component (unit) tests for {@link ReputationEventPublisher}, per ISTQB CTFL v4.0.1 Section
 * 2.2.1. Verifies the published {@link ReputationAwardEvent} carries the right {@code revoke}
 * flag and fields — the actual award/revoke side effect is covered separately in {@code
 * ReputationAwardListenerTest} and {@code ReputationServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class ReputationEventPublisherTest {

  private static final Integer USER_ID = 1;
  private static final String SOURCE_ID = "100:2";

  @Mock private ApplicationEventPublisher eventPublisher;

  @InjectMocks private ReputationEventPublisher reputationEventPublisher;

  @Captor private ArgumentCaptor<ReputationAwardEvent> eventCaptor;

  @Nested
  @DisplayName("award")
  class AwardTests {

    @Test
    @DisplayName("should publish an event with revoke=false")
    void shouldPublishEvent_withRevokeFalse() {
      // When
      reputationEventPublisher.award(USER_ID, RepSourceType.REACTION_RECEIVED, SOURCE_ID);

      // Then
      verify(eventPublisher).publishEvent(eventCaptor.capture());
      ReputationAwardEvent event = eventCaptor.getValue();
      assertThat(event.getUserId()).isEqualTo(USER_ID);
      assertThat(event.getSourceType()).isEqualTo(RepSourceType.REACTION_RECEIVED);
      assertThat(event.getSourceId()).isEqualTo(SOURCE_ID);
      assertThat(event.isRevoke()).isFalse();
    }
  }

  @Nested
  @DisplayName("revoke")
  class RevokeTests {

    @Test
    @DisplayName("should publish an event with revoke=true")
    void shouldPublishEvent_withRevokeTrue() {
      // When
      reputationEventPublisher.revoke(USER_ID, RepSourceType.REACTION_RECEIVED, SOURCE_ID);

      // Then
      verify(eventPublisher).publishEvent(eventCaptor.capture());
      ReputationAwardEvent event = eventCaptor.getValue();
      assertThat(event.getUserId()).isEqualTo(USER_ID);
      assertThat(event.getSourceType()).isEqualTo(RepSourceType.REACTION_RECEIVED);
      assertThat(event.getSourceId()).isEqualTo(SOURCE_ID);
      assertThat(event.isRevoke()).isTrue();
    }
  }
}
