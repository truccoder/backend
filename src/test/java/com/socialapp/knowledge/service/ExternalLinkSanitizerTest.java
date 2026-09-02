package com.socialapp.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.socialapp.knowledge.dto.ExplanationResponseDto.ExternalLink;

/**
 * Component tests for {@link ExternalLinkSanitizer} (backend-plan B34). The backend twin of the
 * frontend's {@code explanation-links.test.ts}, kept in step with it: a broken further-reading
 * link must not reach the card <em>or</em> the saved copy.
 */
class ExternalLinkSanitizerTest {

  private static ExternalLink link(String url) {
    ExternalLink l = new ExternalLink();
    l.setTitle("t");
    l.setUrl(url);
    return l;
  }

  @Test
  @DisplayName("keeps a clean https URL, order preserved")
  void keepsCleanLinks() {
    List<ExternalLink> in =
        List.of(link("https://docs.oracle.com/en/java/"), link("http://martinfowler.com/bliki/"));

    assertThat(ExternalLinkSanitizer.followable(in))
        .extracting(ExternalLink::getUrl)
        .containsExactly("https://docs.oracle.com/en/java/", "http://martinfowler.com/bliki/");
  }

  @ParameterizedTest
  @DisplayName("drops the links a reader could not follow")
  @ValueSource(
      strings = {
        "https://datadoghq.com/blog/observability-படுகின்றன/setup", // non-ASCII spliced into path
        "ftp://example.com/file", // wrong scheme
        "https://localhost/guide", // host with no dot
        "not a url", // unparseable
        "   ", // blank
        "//example.com/x" // no scheme
      })
  void dropsUnfollowableLinks(String url) {
    assertThat(ExternalLinkSanitizer.followable(List.of(link(url)))).isEmpty();
  }

  @Test
  @DisplayName("tolerates a null list, an empty list and null entries / null urls")
  void toleratesNulls() {
    assertThat(ExternalLinkSanitizer.followable(null)).isEmpty();
    assertThat(ExternalLinkSanitizer.followable(List.of())).isEmpty();
    assertThat(
            ExternalLinkSanitizer.followable(
                Arrays.asList(null, link(null), link("https://ok.dev"))))
        .extracting(ExternalLink::getUrl)
        .containsExactly("https://ok.dev");
  }
}
