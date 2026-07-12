package com.socialapp.security.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Component (unit) tests for {@link EmailNormalizer}, per ISTQB CTFL v4.0.1 Section 2.2.1.
 */
class EmailNormalizerTest {

  @Test
  @DisplayName("should lowercase a mixed-case email")
  void shouldLowercaseMixedCaseEmail() {
    assertThat(EmailNormalizer.normalize("User@Example.COM")).isEqualTo("user@example.com");
  }

  @Test
  @DisplayName("should trim leading and trailing whitespace")
  void shouldTrimWhitespace() {
    assertThat(EmailNormalizer.normalize("  user@example.com  ")).isEqualTo("user@example.com");
  }

  @Test
  @DisplayName("should leave an already-normalized email unchanged")
  void shouldLeaveAlreadyNormalizedEmailUnchanged() {
    assertThat(EmailNormalizer.normalize("user@example.com")).isEqualTo("user@example.com");
  }
}
