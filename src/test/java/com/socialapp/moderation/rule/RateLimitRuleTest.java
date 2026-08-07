package com.socialapp.moderation.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.moderation.enums.ViolationType;

/**
 * Component (unit) tests for {@link RateLimitRule}, per ISTQB CTFL v4.0.1 Section 2.2.1 (component
 * testing). {@link SpamDetector} is mocked.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitRuleTest {

  @Mock private SpamDetector spamDetector;

  @InjectMocks private RateLimitRule rateLimitRule;

  @Test
  @DisplayName("shouldReturnSpam_whenAuthorIsRateLimited")
  void shouldReturnSpam_whenAuthorIsRateLimited() {
    when(spamDetector.isRateLimited(1)).thenReturn(true);

    assertThat(rateLimitRule.check(1, "hello world")).isEqualTo(ViolationType.SPAM);
  }

  @Test
  @DisplayName("shouldReturnNull_whenAuthorIsNotRateLimited")
  void shouldReturnNull_whenAuthorIsNotRateLimited() {
    when(spamDetector.isRateLimited(1)).thenReturn(false);

    assertThat(rateLimitRule.check(1, "hello world")).isNull();
  }
}
