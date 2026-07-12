package com.socialapp.search.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Component (unit) tests for {@link SearchQuerySanitizer}, per ISTQB CTFL v4.0.1 Section 2.2.1.
 */
class SearchQuerySanitizerTest {

  @Test
  @DisplayName("should return an empty string when the input is null")
  void shouldReturnEmptyString_whenInputIsNull() {
    assertThat(SearchQuerySanitizer.sanitize(null)).isEmpty();
  }

  @Test
  @DisplayName("should trim leading and trailing whitespace")
  void shouldTrimWhitespace() {
    assertThat(SearchQuerySanitizer.sanitize("  hello  ")).isEqualTo("hello");
  }

  @Test
  @DisplayName("should escape LIKE wildcard characters and backslashes")
  void shouldEscapeLikeWildcardsAndBackslashes() {
    assertThat(SearchQuerySanitizer.sanitize("100%_off\\sale")).isEqualTo("100\\%\\_off\\\\sale");
  }

  @Test
  @DisplayName("should leave an already-plain query unchanged")
  void shouldLeavePlainQueryUnchanged() {
    assertThat(SearchQuerySanitizer.sanitize("hello world")).isEqualTo("hello world");
  }
}
