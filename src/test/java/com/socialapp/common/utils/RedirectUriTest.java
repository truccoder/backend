package com.socialapp.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Component (unit) tests for {@link RedirectUri}, per ISTQB CTFL v4.0.1 Section 2.2.1.
 *
 * <p>The motivating defect: infra set {@code GOOGLE_OAUTH_REDIRECT_URI} to
 * {@code https://elitenexus.id.vn//oauth/google/callback} (doubled slash, because {@code PUBLIC_URL}
 * ended in {@code /}), and Google answered {@code Error 400: redirect_uri_mismatch}.
 */
class RedirectUriTest {

  @Test
  @DisplayName("collapses a doubled slash after the host")
  void collapsesDoubledSlashAfterHost() {
    assertThat(RedirectUri.normalize("https://elitenexus.id.vn//oauth/google/callback"))
        .isEqualTo("https://elitenexus.id.vn/oauth/google/callback");
  }

  @ParameterizedTest
  @CsvSource({
    "https://host//oauth//google///callback, https://host/oauth/google/callback",
    "https://host/oauth/google/callback/,   https://host/oauth/google/callback",
    "http://localhost:3000/oauth/google/callback, http://localhost:3000/oauth/google/callback",
    "'  https://host/cb  ', https://host/cb"
  })
  void normalizesHostAndPath(String input, String expected) {
    assertThat(RedirectUri.normalize(input)).isEqualTo(expected);
  }

  @Test
  @DisplayName("leaves the scheme's own '//' intact")
  void keepsSchemeSlashes() {
    assertThat(RedirectUri.normalize("https://host")).isEqualTo("https://host");
  }

  @Test
  @DisplayName("does not touch the query string")
  void keepsQueryString() {
    assertThat(RedirectUri.normalize("https://host//cb?next=a//b"))
        .isEqualTo("https://host/cb?next=a//b");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "not-a-url", "/oauth/google/callback"})
  @DisplayName("returns blank / schemeless input unchanged (trimmed)")
  void passesThroughWhenNothingToNormalize(String input) {
    String result = RedirectUri.normalize(input);
    if (input == null) {
      assertThat(result).isNull();
    } else {
      assertThat(result).isEqualTo(input.isBlank() ? input : input.strip());
    }
  }
}
