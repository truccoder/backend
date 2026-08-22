package com.socialapp.notifications.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.socialapp.notifications.dto.NotificationResponseDto;
import com.socialapp.notifications.entity.enums.NotificationType;

/**
 * Component (unit) tests for {@link NotificationStreamService}, per ISTQB CTFL v4.0.1 (Section
 * 2.2.1 component testing, Section 4.3.2 branch testing, Section 2.1.3 BDD Given/When/Then).
 *
 * <p><b>The registry is what is under test, not the wire format.</b> An SSE endpoint that delivers
 * correctly but never forgets a dead subscriber is a slow memory leak that only shows up in
 * production, so every path that ends a stream — completion, timeout, error, a failed write — is
 * asserted to remove its entry. The emitters are real {@link SseEmitter}s with no servlet response
 * behind them, which is exactly the state a disconnected client leaves behind.
 */
class NotificationStreamServiceTest {

  private static final Integer USER_ID = 9001;
  private static final Integer OTHER_USER_ID = 9002;

  private NotificationStreamService service;

  @BeforeEach
  void setUp() {
    service = new NotificationStreamService();
  }

  private static NotificationResponseDto notification(Integer id) {
    return NotificationResponseDto.builder()
        .id(id)
        .type(NotificationType.SKILL_VERIFIED)
        .title("Skill verified")
        .build();
  }

  @Nested
  @DisplayName("subscribe")
  class SubscribeTests {

    @Test
    @DisplayName("should register the stream so the user is reachable")
    void shouldRegisterStream() {
      // Given / When
      service.subscribe(USER_ID);

      // Then
      assertThat(service.openStreamCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("should hold several streams for one user — tabs and devices are separate")
    void shouldHoldSeveralStreamsPerUser() {
      // Given / When
      service.subscribe(USER_ID);
      service.subscribe(USER_ID);

      // Then — a single emitter per user would silently kill the older tab
      assertThat(service.openStreamCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("should forget a stream that has completed, on the next write")
    void shouldForgetCompletedStream() {
      // Given — a closed tab. In production the container invokes the onCompletion callback
      // registered by subscribe(); with no container behind the emitter that callback cannot
      // fire, so what this pins is the backstop: the next write to a completed emitter removes
      // it. Both paths have to work, because either one alone leaves the registry growing when
      // the other does not run.
      SseEmitter emitter = service.subscribe(USER_ID);
      emitter.complete();

      // When
      service.publish(USER_ID, notification(1));

      // Then
      assertThat(service.openStreamCount()).isZero();
    }

    @Test
    @DisplayName("should forget a stream that ended with an error, on the next write")
    void shouldForgetErroredStream() {
      // Given
      SseEmitter emitter = service.subscribe(USER_ID);
      emitter.completeWithError(new IOException("client vanished"));

      // When
      service.publish(USER_ID, notification(1));

      // Then
      assertThat(service.openStreamCount()).isZero();
    }
  }

  @Nested
  @DisplayName("publish")
  class PublishTests {

    @Test
    @DisplayName("should do nothing when the user has no stream open")
    void shouldDoNothingWithoutSubscribers() {
      // Given: nobody subscribed — the ordinary case, since most users are not looking at the app

      // When / Then
      assertThatCode(() -> service.publish(USER_ID, notification(1))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should not deliver one user's notification to another user's stream")
    void shouldNotCrossUsers() {
      // Given
      service.subscribe(OTHER_USER_ID);

      // When — the recipient has nothing open, so this has nowhere to go
      service.publish(USER_ID, notification(1));

      // Then — the other user's stream is untouched and still registered
      assertThat(service.openStreamCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("should drop a stream whose write fails instead of failing the send")
    void shouldDropStreamOnFailedWrite() {
      // Given — a completed emitter is what a closed tab leaves behind: still in the registry,
      // no longer writable
      SseEmitter emitter = service.subscribe(USER_ID);
      emitter.complete();
      service.subscribe(USER_ID);

      // When
      assertThatCode(() -> service.publish(USER_ID, notification(1))).doesNotThrowAnyException();

      // Then — the dead one is gone; the live one survives. A notification send runs @Async and
      // must not be brought down by one departed subscriber.
      assertThat(service.openStreamCount()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("heartbeat")
  class HeartbeatTests {

    @Test
    @DisplayName("should not throw when there is nothing to write to")
    void shouldTolerateNoSubscribers() {
      // Given / When / Then — this runs on a schedule forever, including on an idle instance
      assertThatCode(() -> service.heartbeat()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should reap streams that have gone away")
    void shouldReapDeadStreams() {
      // Given
      SseEmitter dead = service.subscribe(USER_ID);
      service.subscribe(OTHER_USER_ID);
      dead.complete();

      // When
      service.heartbeat();

      // Then — this is the second half of why the heartbeat exists: it surfaces a broken path
      // rather than only keeping a working one open
      assertThat(service.openStreamCount()).isEqualTo(1);
    }
  }
}
