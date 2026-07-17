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
 * Component (unit) tests for {@link DuplicateContentRule}, per ISTQB CTFL v4.0.1 Section 2.2.1
 * (component testing). {@link SpamDetector} is mocked.
 */
@ExtendWith(MockitoExtension.class)
class DuplicateContentRuleTest {

  @Mock private SpamDetector spamDetector;

  @InjectMocks private DuplicateContentRule duplicateContentRule;

  @Test
  @DisplayName("shouldReturnDuplicateContent_whenContentIsDuplicate")
  void shouldReturnDuplicateContent_whenContentIsDuplicate() {
    when(spamDetector.isDuplicateContent(1, "hello world")).thenReturn(true);

    assertThat(duplicateContentRule.check(1, "hello world"))
        .isEqualTo(ViolationType.DUPLICATE_CONTENT);
  }

  @Test
  @DisplayName("shouldReturnNull_whenContentIsNotDuplicate")
  void shouldReturnNull_whenContentIsNotDuplicate() {
    when(spamDetector.isDuplicateContent(1, "hello world")).thenReturn(false);

    assertThat(duplicateContentRule.check(1, "hello world")).isNull();
  }
}
