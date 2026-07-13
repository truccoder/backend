package com.socialapp.moderation.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import com.socialapp.moderation.config.ModerationProperties;

/**
 * Component (unit) tests for {@link SpamDetector}, per ISTQB CTFL v4.0.1 Section 2.2.1 (component
 * testing). {@link StringRedisTemplate} is mocked — no real Redis call is ever made.
 */
@ExtendWith(MockitoExtension.class)
class SpamDetectorTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;

  private final ModerationProperties properties = new ModerationProperties();

  private SpamDetector spamDetector;

  @BeforeEach
  void setUp() {
    spamDetector = new SpamDetector(redisTemplate, properties);
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

    @Test
    @DisplayName("shouldReturnFalse_andSetExpiry_onTheFirstPost")
    void shouldReturnFalse_andSetExpiry_onTheFirstPost() {
      // Given
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.increment(anyString())).thenReturn(1L);

      // When
      boolean result = spamDetector.isRateLimited(1);

      // Then — expiry is set only on the first post within the window
      assertThat(result).isFalse();
      verify(redisTemplate).expire(anyString(), eq(60L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("shouldReturnFalse_andNotResetExpiry_whenBelowTheLimit_boundary")
    void shouldReturnFalse_andNotResetExpiry_whenBelowTheLimit_boundary() {
      // Given — exactly at MAX_POSTS_PER_MINUTE (5), still allowed
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.increment(anyString())).thenReturn(5L);

      // When
      boolean result = spamDetector.isRateLimited(1);

      // Then
      assertThat(result).isFalse();
      verify(redisTemplate, never()).expire(anyString(), eq(60L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("shouldReturnTrue_whenAboveTheLimit_boundary")
    void shouldReturnTrue_whenAboveTheLimit_boundary() {
      // Given — one past MAX_POSTS_PER_MINUTE (5)
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.increment(anyString())).thenReturn(6L);

      // When
      boolean result = spamDetector.isRateLimited(1);

      // Then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("shouldReturnFalse_whenRedisReturnsNull")
    void shouldReturnFalse_whenRedisReturnsNull() {
      // Given — a Redis outage on increment() must not crash or falsely rate-limit the user
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.increment(anyString())).thenReturn(null);

      // When
      boolean result = spamDetector.isRateLimited(1);

      // Then
      assertThat(result).isFalse();
      verify(redisTemplate, never()).expire(anyString(), eq(60L), eq(TimeUnit.SECONDS));
    }
  }
}
