package com.socialapp.moderation.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.socialapp.common.ratelimit.FixedWindowRateLimiter;
import com.socialapp.moderation.config.ModerationProperties;

/**
 * Component (unit) tests for {@link SpamDetector}, per ISTQB CTFL v4.0.1 Section 2.2.1 (component
 * testing). {@link StringRedisTemplate} is mocked — no real Redis call is ever made.
 */
@ExtendWith(MockitoExtension.class)
class SpamDetectorTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private FixedWindowRateLimiter rateLimiter;
  @Mock private ValueOperations<String, String> valueOperations;

  private final ModerationProperties properties = new ModerationProperties();

  private SpamDetector spamDetector;

  @BeforeEach
  void setUp() {
    spamDetector = new SpamDetector(redisTemplate, properties, rateLimiter);
  }

  @Nested
  @DisplayName("isDuplicateContent")
  class IsDuplicateContentTests {

    @Test
    @DisplayName("shouldReturnFalse_whenContentIsBlank")
    void shouldReturnFalse_whenContentIsBlank() {
      // Given / When
      boolean result = spamDetector.isDuplicateContent(1, "   ");

      // Then — blank content never touches Redis
      assertThat(result).isFalse();
      verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("shouldReturnFalse_whenContentHashIsSeenForTheFirstTime")
    void shouldReturnFalse_whenContentHashIsSeenForTheFirstTime() {
      // Given
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(60L), eq(TimeUnit.SECONDS)))
          .thenReturn(true);

      // When
      boolean result = spamDetector.isDuplicateContent(1, "hello world");

      // Then
      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("shouldReturnTrue_whenContentHashAlreadyExists")
    void shouldReturnTrue_whenContentHashAlreadyExists() {
      // Given — setIfAbsent returns false, meaning the key was already present
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(60L), eq(TimeUnit.SECONDS)))
          .thenReturn(false);

      // When
      boolean result = spamDetector.isDuplicateContent(1, "hello world");

      // Then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("shouldReturnFalse_whenRedisReturnsNull")
    void shouldReturnFalse_whenRedisReturnsNull() {
      // Given — Boolean.FALSE.equals(null) is false, so a null reply must not be treated as
      // a duplicate
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(60L), eq(TimeUnit.SECONDS)))
          .thenReturn(null);

      // When
      boolean result = spamDetector.isDuplicateContent(1, "hello world");

      // Then
      assertThat(result).isFalse();
    }
  }

  @Nested
  @DisplayName("isRateLimited")
  class IsRateLimitedTests {

    private static final String KEY = "moderation:rate:1";
    private static final Duration WINDOW = Duration.ofMinutes(1);

    @Test
    @DisplayName("shouldDelegateToTheSharedLimiter_withTheAuthorAsTheKey")
    void shouldDelegateToTheSharedLimiter() {
      // Given — the counting itself moved to FixedWindowRateLimiter, which is where the atomicity
      // of INCR-plus-EXPIRE is now tested. What matters here is that SpamDetector asks it with the
      // right key and budget rather than counting again on its own: the local copy set the TTL as a
      // separate command, so an interruption between the two left the author permanently unable to
      // post, and it had no try/catch, so a Redis outage threw all the way out through the rule
      // engine into createPost.
      when(rateLimiter.isOverLimit(KEY, 5, WINDOW)).thenReturn(false);

      // When
      boolean result = spamDetector.isRateLimited(1);

      // Then
      assertThat(result).isFalse();
      verify(rateLimiter).isOverLimit(KEY, 5, WINDOW);
      verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("shouldReturnTrue_whenTheSharedLimiterSaysTheBudgetIsSpent")
    void shouldReturnTrue_whenOverBudget() {
      // Given
      when(rateLimiter.isOverLimit(KEY, 5, WINDOW)).thenReturn(true);

      // When / Then
      assertThat(spamDetector.isRateLimited(1)).isTrue();
    }
  }
}
