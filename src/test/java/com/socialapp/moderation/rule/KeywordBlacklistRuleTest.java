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
 * Component (unit) tests for {@link KeywordBlacklistRule}, per ISTQB CTFL v4.0.1 Section 2.2.1
 * (component testing). {@link KeywordFilter} is mocked.
 */
@ExtendWith(MockitoExtension.class)
class KeywordBlacklistRuleTest {

  @Mock private KeywordFilter keywordFilter;

  @InjectMocks private KeywordBlacklistRule keywordBlacklistRule;

  @Test
  @DisplayName("shouldReturnKeywordBlacklist_whenContentIsBlacklisted")
  void shouldReturnKeywordBlacklist_whenContentIsBlacklisted() {
    when(keywordFilter.containsBlacklistedContent("bad content")).thenReturn(true);

    assertThat(keywordBlacklistRule.check(1, "bad content"))
        .isEqualTo(ViolationType.KEYWORD_BLACKLIST);
  }

  @Test
  @DisplayName("shouldReturnNull_whenContentIsNotBlacklisted")
  void shouldReturnNull_whenContentIsNotBlacklisted() {
    when(keywordFilter.containsBlacklistedContent("hello world")).thenReturn(false);

    assertThat(keywordBlacklistRule.check(1, "hello world")).isNull();
  }
}
