package com.socialapp.reputation.event;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.reputation.entity.enums.RepSourceType;
import com.socialapp.reputation.service.ReputationService;

/**
 * Component (unit) tests for {@link ReputationAwardListener}, per ISTQB CTFL v4.0.1 (Section
 * 2.2.1 component testing; Section 4.3.1 state transition testing over the {@code revoke} flag;
 * Section 4.5.3 Error Guessing — a failure processing one event must be swallowed, not
 * propagated, since this runs {@code @Async} after the triggering transaction has already
 * committed and there is no caller left to receive the exception).
 */
@ExtendWith(MockitoExtension.class)
class ReputationAwardListenerTest {

  private static final Integer USER_ID = 1;
  private static final String SOURCE_ID = "100:2";

  @Mock private ReputationService reputationService;

  @InjectMocks private ReputationAwardListener reputationAwardListener;

  private static ReputationAwardEvent event(boolean revoke) {
    return ReputationAwardEvent.builder()
        .userId(USER_ID)
        .sourceType(RepSourceType.REACTION_RECEIVED)
        .sourceId(SOURCE_ID)
        .revoke(revoke)
        .build();
  }

  @Nested
  @DisplayName("handleReputationEvent")
  class HandleReputationEventTests {

    @Test
    @DisplayName("should call award when the event is not a revoke")
    void shouldCallAward_whenEventIsNotRevoke() {
      // When
      reputationAwardListener.handleReputationEvent(event(false));

      // Then
      verify(reputationService).award(USER_ID, RepSourceType.REACTION_RECEIVED, SOURCE_ID);
      verify(reputationService, never())
          .revoke(USER_ID, RepSourceType.REACTION_RECEIVED, SOURCE_ID);
    }

    @Test
    @DisplayName("should call revoke when the event is a revoke")
    void shouldCallRevoke_whenEventIsRevoke() {
      // When
      reputationAwardListener.handleReputationEvent(event(true));

      // Then
      verify(reputationService).revoke(USER_ID, RepSourceType.REACTION_RECEIVED, SOURCE_ID);
      verify(reputationService, never()).award(USER_ID, RepSourceType.REACTION_RECEIVED, SOURCE_ID);
    }

    @Test
    @DisplayName("should swallow the exception when the service call fails")
    void shouldSwallowException_whenServiceCallFails() {
      // Given
      doThrow(new RuntimeException("DB unavailable"))
          .when(reputationService)
          .award(USER_ID, RepSourceType.REACTION_RECEIVED, SOURCE_ID);

      // When / Then — must not propagate, since this runs after the caller's transaction has
      // already committed and no one is left to catch it
      reputationAwardListener.handleReputationEvent(event(false));
    }
  }
}
