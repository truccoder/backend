package com.socialapp.moderation.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.moderation.dto.ModerationResult;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.enums.ViolationType;

/**
 * Component (unit) tests for {@link ModerationRuleEngine}, per ISTQB CTFL v4.0.1 Section 2.2.1
 * (component testing). {@link KeywordFilter} and {@link SpamDetector} are mocked so only the
 * ordering/short-circuit logic of {@code evaluate} is under test.
 */
@ExtendWith(MockitoExtension.class)
class ModerationRuleEngineTest {

  @Mock private KeywordFilter keywordFilter;
  @Mock private SpamDetector spamDetector;

  @InjectMocks private ModerationRuleEngine ruleEngine;

  @Test
  @DisplayName("shouldRejectForSpam_whenAuthorIsRateLimited")
  void shouldRejectForSpam_whenAuthorIsRateLimited() {
    // Given
    when(spamDetector.isRateLimited(1)).thenReturn(true);

    // When
    ModerationResult result = ruleEngine.evaluate(1, "hello world");

    // Then
    assertThat(result.getStatus()).isEqualTo(ModerationStatus.REJECTED);
    assertThat(result.getViolations()).containsExactly(ViolationType.SPAM);
  }

  @Test
  @DisplayName("shouldRejectForDuplicateContent_whenNotRateLimitedButContentIsDuplicate")
  void shouldRejectForDuplicateContent_whenNotRateLimitedButContentIsDuplicate() {
    // Given
    when(spamDetector.isRateLimited(1)).thenReturn(false);
    when(spamDetector.isDuplicateContent(1, "hello world")).thenReturn(true);

    // When
    ModerationResult result = ruleEngine.evaluate(1, "hello world");

    // Then
    assertThat(result.getStatus()).isEqualTo(ModerationStatus.REJECTED);
    assertThat(result.getViolations()).containsExactly(ViolationType.DUPLICATE_CONTENT);
  }

  @Test
  @DisplayName("shouldRejectForKeywordBlacklist_whenNeitherSpamCheckTrips")
  void shouldRejectForKeywordBlacklist_whenNeitherSpamCheckTrips() {
    // Given
    when(spamDetector.isRateLimited(1)).thenReturn(false);
    when(spamDetector.isDuplicateContent(1, "bad content")).thenReturn(false);
    when(keywordFilter.containsBlacklistedContent("bad content")).thenReturn(true);

    // When
    ModerationResult result = ruleEngine.evaluate(1, "bad content");

    // Then
    assertThat(result.getStatus()).isEqualTo(ModerationStatus.REJECTED);
    assertThat(result.getViolations()).containsExactly(ViolationType.KEYWORD_BLACKLIST);
  }

  @Test
  @DisplayName("shouldReturnPendingModeration_withNoViolations_whenAllChecksPass")
  void shouldReturnPendingModeration_withNoViolations_whenAllChecksPass() {
    // Given
    when(spamDetector.isRateLimited(1)).thenReturn(false);
    when(spamDetector.isDuplicateContent(1, "hello world")).thenReturn(false);
    when(keywordFilter.containsBlacklistedContent("hello world")).thenReturn(false);

    // When
    ModerationResult result = ruleEngine.evaluate(1, "hello world");

    // Then
    assertThat(result.getStatus()).isEqualTo(ModerationStatus.PENDING_MODERATION);
    assertThat(result.getViolations()).isEmpty();
  }
}
