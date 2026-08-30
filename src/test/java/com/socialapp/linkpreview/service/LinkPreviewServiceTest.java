package com.socialapp.linkpreview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.socialapp.common.exception.ExternalApiException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.common.ratelimit.FixedWindowRateLimiter;
import com.socialapp.linkpreview.dto.LinkPreviewResponseDto;

/**
 * Component tests for {@link LinkPreviewService}, per ISTQB CTFL v4.0.1 Section 2.2.2.
 *
 * <p>The HTTP client is mocked and the safety guard is <b>real</b>. That combination is deliberate:
 * the behaviour worth pinning down here is what happens between the two — that every redirect hop
 * goes back through the guard, that the body is capped, that a failure comes back as the right
 * kind of error — and a mocked guard would let a redirect to {@code 169.254.169.254} sail through a
 * test that then proved nothing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LinkPreviewServiceTest {

  private static final Integer USER_ID = 9001;
  private static final String PUBLIC_URL = "https://93.184.216.34/article";

  @Mock private HttpClient httpClient;
  @Mock private FixedWindowRateLimiter rateLimiter;

  private LinkPreviewService linkPreviewService;

  @BeforeEach
  void setUp() {
    linkPreviewService =
        new LinkPreviewService(
            httpClient, new UrlSafetyGuard(), new OpenGraphParser(), rateLimiter);
    when(rateLimiter.isOverLimit(anyString(), anyInt(), any())).thenReturn(false);
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<InputStream> response(int status, String contentType, String body) {
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body())
        .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    when(response.headers())
        .thenReturn(HttpHeaders.of(Map.of("content-type", List.of(contentType)), (k, v) -> true));
    return response;
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<InputStream> redirect(int status, String location) {
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
    when(response.headers())
        .thenReturn(HttpHeaders.of(Map.of("location", List.of(location)), (k, v) -> true));
    return response;
  }

  /**
   * Queues one response per call, repeating the last one once the queue runs dry — so a test that
   * only cares about the first hop does not have to enumerate the rest.
   */
  @SafeVarargs
  private void answerWith(HttpResponse<InputStream>... responses) throws Exception {
    Deque<HttpResponse<InputStream>> queue = new ArrayDeque<>(List.of(responses));
    when(httpClient.send(any(HttpRequest.class), any()))
        .thenAnswer(invocation -> queue.size() > 1 ? queue.poll() : queue.peek());
  }

  @Nested
  @DisplayName("preview")
  class PreviewTests {

    @Test
    @DisplayName("shouldReturnWhatThePageSaysAboutItself_happyPath")
    void shouldReturnPageMetadata() throws Exception {
      // Given
      answerWith(
          response(
              200,
              "text/html; charset=UTF-8",
              "<meta property=\"og:title\" content=\"A title\">"
                  + "<meta property=\"og:image\" content=\"/cover.png\">"));

      // When
      LinkPreviewResponseDto preview = linkPreviewService.preview(USER_ID, PUBLIC_URL);

      // Then — the relative image resolved against the page it came from
      assertThat(preview.title()).isEqualTo("A title");
      assertThat(preview.thumbnailUrl()).isEqualTo("https://93.184.216.34/cover.png");
    }

    @Test
    @DisplayName("shouldIdentifyItselfInTheUserAgent")
    void shouldSendAUserAgent() throws Exception {
      // Given
      answerWith(response(200, "text/html", "<title>x</title>"));

      // When
      linkPreviewService.preview(USER_ID, PUBLIC_URL);

      // Then — a crawler that will not say who it is is a crawler nobody can ask to stop
      ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
      verify(httpClient).send(request.capture(), any());
      assertThat(request.getValue().headers().firstValue("User-Agent"))
          .contains("EliteNexusLinkPreview/1.0");
    }

    @Test
    @DisplayName("shouldFollowARedirectAndReadTheFinalPage")
    void shouldFollowRedirect() throws Exception {
      // Given — a shortened link, which is what most pasted links are
      answerWith(
          redirect(301, "https://8.8.8.8/final"),
          response(200, "text/html", "<meta property=\"og:title\" content=\"Moved here\">"));

      // When
      LinkPreviewResponseDto preview = linkPreviewService.preview(USER_ID, PUBLIC_URL);

      // Then
      assertThat(preview.title()).isEqualTo("Moved here");
      assertThat(preview.siteName()).isEqualTo("8.8.8.8");
      verify(httpClient, times(2)).send(any(), any());
    }

    @Test
    @DisplayName("shouldRefuseARedirectPointingInsideThePerimeter_security")
    void shouldRefuseRedirectToPrivateAddress() throws Exception {
      // Given — the attack the manual redirect loop exists for: a public host that answers with a
      // Location header aimed at the cloud metadata service, which hands out credentials
      answerWith(redirect(302, "http://169.254.169.254/latest/meta-data/"));

      // When / Then
      assertThatThrownBy(() -> linkPreviewService.preview(USER_ID, PUBLIC_URL))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("private network");

      // Then — and the second request was never made
      verify(httpClient, times(1)).send(any(), any());
    }

    @Test
    @DisplayName("shouldGiveUpAfterFourHops_boundary")
    void shouldStopAfterTooManyRedirects() throws Exception {
      // Given — BVA on MAX_REDIRECTS (3): one initial request plus three followed hops, and the
      // fourth redirect ends it. A loop of two pages redirecting to each other never terminates
      // without this.
      answerWith(
          redirect(302, "https://8.8.8.8/a"),
          redirect(302, "https://8.8.8.8/b"),
          redirect(302, "https://8.8.8.8/c"),
          redirect(302, "https://8.8.8.8/d"));

      // When / Then
      assertThatThrownBy(() -> linkPreviewService.preview(USER_ID, PUBLIC_URL))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("redirects too many times");
      verify(httpClient, times(4)).send(any(), any());
    }

    @Test
    @DisplayName("shouldReportARedirectWithNoDestinationAsAFailedFetch")
    void shouldRejectRedirectWithoutLocation() throws Exception {
      // Given
      @SuppressWarnings("unchecked")
      HttpResponse<InputStream> headerless = mock(HttpResponse.class);
      when(headerless.statusCode()).thenReturn(302);
      when(headerless.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
      when(headerless.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));
      answerWith(headerless);

      // When / Then
      assertThatThrownBy(() -> linkPreviewService.preview(USER_ID, PUBLIC_URL))
          .isInstanceOf(ExternalApiException.class);
    }

    @Test
    @DisplayName("shouldReadOnlyTheFirstHalfMegabyte_boundary")
    void shouldCapTheBodySize() throws Exception {
      // Given — BVA on MAX_BODY_BYTES: the tags live in <head>, and a page that streams forever
      // satisfies a response timeout forever. The title sits inside the cap; the marker after it
      // does not.
      String head = "<title>Early title</title>";
      String filler = "x".repeat(512 * 1024);
      answerWith(
          response(
              200,
              "text/html",
              head
                  + filler
                  + "<meta property=\"og:image\" "
                  + "content=\"https://cdn.example.com/too-late.png\">"));

      // When
      LinkPreviewResponseDto preview = linkPreviewService.preview(USER_ID, PUBLIC_URL);

      // Then
      assertThat(preview.title()).isEqualTo("Early title");
      assertThat(preview.thumbnailUrl()).isNull();
    }

    @Test
    @DisplayName("shouldTreatALinkStraightToAnImageAsItsOwnThumbnail")
    void shouldPreviewAnImageUrl() throws Exception {
      // Given — pasting an image link is a thing people do, and such a link describes itself
      answerWith(response(200, "image/png", "PNG"));

      // When
      LinkPreviewResponseDto preview = linkPreviewService.preview(USER_ID, PUBLIC_URL);

      // Then
      assertThat(preview.thumbnailUrl()).isEqualTo(PUBLIC_URL);
      assertThat(preview.title()).isNull();
    }

    @Test
    @DisplayName("shouldReturnAnEmptyPreviewForContentWithNoMetadataToRead")
    void shouldReturnEmptyPreviewForNonHtml() throws Exception {
      // Given — a PDF, a video, a JSON API. Empty is the honest answer, and the composer's
      // fallback is the same either way: let the author type the fields in.
      answerWith(response(200, "application/pdf", "%PDF-1.7"));

      // When
      LinkPreviewResponseDto preview = linkPreviewService.preview(USER_ID, PUBLIC_URL);

      // Then
      assertThat(preview.title()).isNull();
      assertThat(preview.thumbnailUrl()).isNull();
      assertThat(preview.siteName()).isEqualTo("93.184.216.34");
    }

    @Test
    @DisplayName("shouldReportAnErrorStatusFromTheTargetAs503_notAsAnEmptyPreview")
    void shouldSurfaceErrorStatus() throws Exception {
      // Given
      answerWith(response(404, "text/html", "<title>Not found</title>"));

      // When / Then — ExternalApiException maps to a retryable 503; a silent empty preview would
      // look identical to a page that simply has no metadata
      assertThatThrownBy(() -> linkPreviewService.preview(USER_ID, PUBLIC_URL))
          .isInstanceOf(ExternalApiException.class)
          .hasMessageContaining("404");
    }

    @Test
    @DisplayName("shouldSurfaceANetworkFailureAsAnExternalApiFailure")
    void shouldSurfaceNetworkFailure() throws Exception {
      // Given
      when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new IOException("timeout"));

      // When / Then
      assertThatThrownBy(() -> linkPreviewService.preview(USER_ID, PUBLIC_URL))
          .isInstanceOf(ExternalApiException.class);
    }

    @Test
    @DisplayName("shouldRefuseAPrivateAddressBeforeMakingAnyRequestAtAll_security")
    void shouldRefuseBeforeFetching() throws Exception {
      // When / Then
      assertThatThrownBy(() -> linkPreviewService.preview(USER_ID, "http://127.0.0.1:80/admin"))
          .isInstanceOf(ValidationException.class);

      verify(httpClient, never()).send(any(), any());
    }

    @Test
    @DisplayName("shouldStopACallerAskingForPreviewsTooFast")
    void shouldEnforceTheRateLimit() throws Exception {
      // Given — this endpoint spends THIS server's sockets on an address the caller chose
      when(rateLimiter.isOverLimit(anyString(), anyInt(), any())).thenReturn(true);

      // When / Then
      assertThatThrownBy(() -> linkPreviewService.preview(USER_ID, PUBLIC_URL))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Too many");

      verify(httpClient, never()).send(any(), any());
    }

    @Test
    @DisplayName("shouldCountTheRateLimitPerCaller_notGlobally")
    void shouldKeyTheRateLimitOnTheCaller() throws Exception {
      // Given
      answerWith(response(200, "text/html", "<title>x</title>"));

      // When
      linkPreviewService.preview(USER_ID, PUBLIC_URL);

      // Then — a global key would let one busy user lock the feature for everybody
      verify(rateLimiter)
          .isOverLimit(org.mockito.ArgumentMatchers.contains("9001"), anyInt(), any());
    }
  }

  @Nested
  @DisplayName("findImage — the crawler's entry point")
  class FindImageTests {

    @Test
    @DisplayName("shouldReturnTheOpenGraphImage_happyPath")
    void shouldReturnTheImage() throws Exception {
      // Given
      answerWith(
          response(
              200,
              "text/html",
              "<meta property=\"og:image\" content=\"https://cdn.example.com/hn.png\">"));

      // When / Then
      assertThat(linkPreviewService.findImage(PUBLIC_URL))
          .isEqualTo("https://cdn.example.com/hn.png");
    }

    @Test
    @DisplayName("shouldSwallowFailuresRatherThanCostTheCrawlerItsBatch")
    void shouldSwallowFailures() throws Exception {
      // Given — the caller is a scheduled job filling in an optional column, where a story with
      // no picture is a normal outcome and an escaping exception costs the whole batch
      when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new IOException("down"));

      // When / Then
      assertThat(linkPreviewService.findImage(PUBLIC_URL)).isNull();
      assertThat(linkPreviewService.findImage("http://127.0.0.1/")).isNull();
      assertThat(linkPreviewService.findImage("not a url at all")).isNull();
    }
  }
}
