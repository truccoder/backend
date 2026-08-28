package com.socialapp.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Component (unit) tests for {@link FixedWindowRateLimiter}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing; Section 4.2.2 boundary value analysis at the limit; Section 4.3.2 branch
 * testing over the fail-open path).
 *
 * <p>The behaviour worth pinning here is <b>atomicity</b>. Counting used to be {@code INCR} followed
 * by a separate {@code EXPIRE}, and anything that interrupted the pair — process death, a dropped
 * connection, a failover — left a counter with no TTL that nothing would ever set one on, because
 * the {@code count == 1} branch was long past. That key then sat above the limit permanently: an IP
 * that could never sign in again, or an email address that could never receive another password
 * reset. These tests assert the two are now one script, which is the only thing that makes that
 * state unreachable.
 */
@ExtendWith(MockitoExtension.class)
class FixedWindowRateLimiterTest {

  private static final String KEY = "ratelimit:test:203.0.113.7";
  private static final Duration WINDOW = Duration.ofMinutes(1);
  private static final int LIMIT = 3;

  @Mock private StringRedisTemplate redisTemplate;

  private FixedWindowRateLimiter limiter;

  @BeforeEach
  void setUp() {
    limiter = new FixedWindowRateLimiter(redisTemplate);
  }

  private void stubCount(Long count) {
    when(redisTemplate.execute(
            ArgumentMatchers.<RedisScript<Long>>any(), anyList(), ArgumentMatchers.<Object>any()))
        .thenReturn(count);
  }

  @Test
  @DisplayName("counts and expires in a single Redis round trip, never as two commands")
  void countsAndExpiresAtomically() {
    // Given
    stubCount(1L);

    // When
    limiter.isOverLimit(KEY, LIMIT, WINDOW);

    // Then — one script carrying the key and the window; no separate EXPIRE, which is the whole
    // point. verifyNoMoreInteractions is what would catch a regression back to two commands.
    verify(redisTemplate)
        .execute(
            ArgumentMatchers.<RedisScript<Long>>any(),
            eq(List.of(KEY)),
            eq(String.valueOf(WINDOW.toMillis())));
    verifyNoMoreInteractions(redisTemplate);
  }

  @Test
  @DisplayName("allows the last request inside the budget and refuses the first one past it")
  void boundaryAtTheLimit() {
    // Given / When / Then — BVA on `count > limit`
    stubCount((long) LIMIT);
    assertThat(limiter.isOverLimit(KEY, LIMIT, WINDOW)).isFalse();

    stubCount(LIMIT + 1L);
    assertThat(limiter.isOverLimit(KEY, LIMIT, WINDOW)).isTrue();
  }

  @Test
  @DisplayName("treats a null reply as not over limit")
  void nullReplyFailsOpen() {
    // Given
    stubCount(null);

    // When / Then
    assertThat(limiter.isOverLimit(KEY, LIMIT, WINDOW)).isFalse();
  }

  @Test
  @DisplayName("fails open when Redis is unreachable")
  void failsOpenWhenRedisIsDown() {
    // Given
    when(redisTemplate.execute(
            ArgumentMatchers.<RedisScript<Long>>any(), anyList(), ArgumentMatchers.<Object>any()))
        .thenThrow(new IllegalStateException("Redis unreachable"));

    // When / Then — a limiter that cannot count is a degraded defence; one that rejects everything
    // is an outage of whatever it was protecting.
    assertThat(limiter.isOverLimit(KEY, LIMIT, WINDOW)).isFalse();
  }
}
