package com.socialapp.moderation.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.moderation.dto.ModerationResult;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.enums.ViolationType;

/**
 * Component (unit) tests for {@link ModerationRuleEngine}, per ISTQB CTFL v4.0.1 Section 2.2.1
 * (component testing). The 3 {@link ModerationRule} implementations are mocked directly so only
 * the engine's own iteration/short-circuit logic is under test (each rule's own delegation logic
 * is covered separately by {@link RateLimitRuleTest}/{@link DuplicateContentRuleTest}/{@link
 * KeywordBlacklistRuleTest}).
 */
@ExtendWith(MockitoExtension.class)
class ModerationRuleEngineTest {

  @Mock private ModerationRule rateLimitRule;
  @Mock private ModerationRule duplicateContentRule;
  @Mock private ModerationRule keywordBlacklistRule;

  private ModerationRuleEngine ruleEngine;

  @BeforeEach
  void setUp() {
    ruleEngine =
        new ModerationRuleEngine(
            List.of(rateLimitRule, duplicateContentRule, keywordBlacklistRule));
  }

  @Test
  @DisplayName("shouldRejectForSpam_whenFirstRuleReportsAViolation")
  void shouldRejectForSpam_whenFirstRuleReportsAViolation() {
    // Given
    when(rateLimitRule.check(1, "hello world")).thenReturn(ViolationType.SPAM);

    // When
    ModerationResult result = ruleEngine.evaluate(1, "hello world");

    // Then
    assertThat(result.getStatus()).isEqualTo(ModerationStatus.REJECTED);
    assertThat(result.getViolations()).containsExactly(ViolationType.SPAM);
  }

  @Test
  @DisplayName("shouldRejectForDuplicateContent_whenFirstRulePassesButSecondReportsAViolation")
  void shouldRejectForDuplicateContent_whenFirstRulePassesButSecondReportsAViolation() {
    // Given
    when(rateLimitRule.check(1, "hello world")).thenReturn(null);
    when(duplicateContentRule.check(1, "hello world")).thenReturn(ViolationType.DUPLICATE_CONTENT);

    // When
    ModerationResult result = ruleEngine.evaluate(1, "hello world");

    // Then
    assertThat(result.getStatus()).isEqualTo(ModerationStatus.REJECTED);
    assertThat(result.getViolations()).containsExactly(ViolationType.DUPLICATE_CONTENT);
  }

  @Test
  @DisplayName("shouldRejectForKeywordBlacklist_whenNeitherEarlierRuleReportsAViolation")
  void shouldRejectForKeywordBlacklist_whenNeitherEarlierRuleReportsAViolation() {
    // Given
    when(rateLimitRule.check(1, "bad content")).thenReturn(null);
    when(duplicateContentRule.check(1, "bad content")).thenReturn(null);
    when(keywordBlacklistRule.check(1, "bad content")).thenReturn(ViolationType.KEYWORD_BLACKLIST);

    // When
    ModerationResult result = ruleEngine.evaluate(1, "bad content");

    // Then
    assertThat(result.getStatus()).isEqualTo(ModerationStatus.REJECTED);
    assertThat(result.getViolations()).containsExactly(ViolationType.KEYWORD_BLACKLIST);
  }

  @Test
  @DisplayName("shouldReturnPendingModeration_withNoViolations_whenAllRulesPass")
  void shouldReturnPendingModeration_withNoViolations_whenAllRulesPass() {
    // Given
    when(rateLimitRule.check(1, "hello world")).thenReturn(null);
    when(duplicateContentRule.check(1, "hello world")).thenReturn(null);
    when(keywordBlacklistRule.check(1, "hello world")).thenReturn(null);

    // When
    ModerationResult result = ruleEngine.evaluate(1, "hello world");

    // Then
    assertThat(result.getStatus()).isEqualTo(ModerationStatus.PENDING_MODERATION);
    assertThat(result.getViolations()).isEmpty();
  }
}
