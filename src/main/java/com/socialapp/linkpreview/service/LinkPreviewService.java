package com.socialapp.linkpreview.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.socialapp.common.exception.ExternalApiException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.common.ratelimit.FixedWindowRateLimiter;
import com.socialapp.linkpreview.dto.LinkPreviewResponseDto;

import lombok.extern.slf4j.Slf4j;

/**
 * Fetches a page the caller pasted and reports what it says about itself.
 *
 * <p>This exists because the browser cannot do it. Reading a stranger's {@code og:image} means
 * downloading that site's HTML, and the same-origin policy stops a client doing so — which is why
 * {@code LinkDetails} has four fields ({@code url}, {@code title}, {@code description}, {@code
 * thumbnailUrl}) that were all typed by hand, and why a {@code LINK} post in practice showed up as
 * a bare URL: nobody goes and finds the title of a link they just pasted.
 *
 * <p>Every limit in here is a bound on what one request can cost or reach, and they only work
 * together — see {@link UrlSafetyGuard} for the address rules, which are the important half.
 */
@Slf4j
@Service
public class LinkPreviewService {

  /**
   * How far a chain of redirects is followed.
   *
   * <p>Followed by hand rather than by {@link HttpClient.Redirect#NORMAL}, because every hop has to
   * go back through {@link UrlSafetyGuard}. A client that follows redirects itself would take the
   * guard's approval of a public first URL as approval of wherever a {@code Location} header
   * pointed next, and "public host answers 302 to 169.254.169.254" is the standard way past a
   * check that only looks at what the user typed.
   */
  private static final int MAX_REDIRECTS = 3;

  /**
   * How much of the body is read.
   *
   * <p>Not the same thing as a timeout: a server that streams slowly but steadily satisfies a
   * response timeout forever. Everything wanted here lives in {@code <head>}, so half a megabyte
   * is generous — and it is a ceiling on memory per concurrent preview as well as on transfer.
   */
  private static final int MAX_BODY_BYTES = 512 * 1024;

  /**
   * Previews one caller may ask for per {@link #RATE_WINDOW}.
   *
   * <p>This endpoint spends <em>this server's</em> outbound bandwidth and sockets on an address the
   * caller chose, which makes an unlimited one a way to point the backend at somebody else's site
   * in volume. Pasting links into a composer is a human-speed activity; twenty a minute is well
   * past what that looks like.
   */
  private static final int RATE_LIMIT = 20;

  private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

  private static final String RATE_KEY_PREFIX = "ratelimit:linkpreview:";

  private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

  /**
   * Sent so the sites being read can identify and, if they want, refuse this traffic. A crawler
   * that will not say who it is is a crawler nobody can ask to stop.
   */
  private static final String USER_AGENT = "EliteNexusLinkPreview/1.0";

  private final HttpClient httpClient;
  private final UrlSafetyGuard urlSafetyGuard;
  private final OpenGraphParser openGraphParser;
  private final FixedWindowRateLimiter rateLimiter;

  public LinkPreviewService(
      @Qualifier("linkPreviewHttpClient") HttpClient httpClient,
      UrlSafetyGuard urlSafetyGuard,
      OpenGraphParser openGraphParser,
      FixedWindowRateLimiter rateLimiter) {
    this.httpClient = httpClient;
    this.urlSafetyGuard = urlSafetyGuard;
    this.openGraphParser = openGraphParser;
    this.rateLimiter = rateLimiter;
  }

  /** What the page at {@code rawUrl} says about itself, for the caller to accept or overwrite. */
  public LinkPreviewResponseDto preview(Integer userId, String rawUrl) {
    if (rateLimiter.isOverLimit(RATE_KEY_PREFIX + userId, RATE_LIMIT, RATE_WINDOW)) {
      throw new ValidationException("Too many link previews requested. Try again in a minute.");
    }
    return describe(fetch(rawUrl));
  }

  /**
   * The image on a page, for a caller that only wants that — used by the Hacker News crawler,
   * whose API supplies no picture of its own.
   *
   * <p>Returns {@code null} for anything that goes wrong rather than throwing. The caller is a
   * background job filling in an optional column, and a story that arrives without a picture is a
   * normal outcome there; letting a failed fetch escape would cost the whole crawl batch.
   */
  public String findImage(String rawUrl) {
    try {
      return describe(fetch(rawUrl)).thumbnailUrl();
    } catch (RuntimeException e) {
      log.debug("No preview image for {}: {}", rawUrl, e.getMessage());
      return null;
    }
  }

  private LinkPreviewResponseDto describe(Page page) {
    String contentType = page.contentType();

    if (contentType.startsWith("text/html") || contentType.startsWith("application/xhtml")) {
      return openGraphParser.parse(page.body(), page.url());
    }

    // A link straight to an image is a thing people paste, and it describes itself: it IS the
    // thumbnail. Anything else — a PDF, a video, a JSON API — has no metadata this can read, and
    // an empty preview is the honest answer rather than an error, since the composer's fallback
    // is the same either way: let the author type the fields in.
    if (contentType.startsWith("image/")) {
      return new LinkPreviewResponseDto(null, null, page.url().toString(), page.url().getHost());
    }

    return new LinkPreviewResponseDto(null, null, null, page.url().getHost());
  }

  /**
   * Follows {@code rawUrl}, re-checking every hop, and returns the first non-redirect response.
   */
  private Page fetch(String rawUrl) {
    URI uri = urlSafetyGuard.requireFetchable(rawUrl);

    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      HttpResponse<InputStream> response = send(uri);

      try (InputStream body = response.body()) {
        int status = response.statusCode();

        if (isRedirect(status)) {
          uri = nextHop(uri, response);
          continue;
        }
        if (status >= 400) {
          throw new ExternalApiException("That link answered with HTTP " + status);
        }

        return new Page(uri, contentTypeOf(response), read(body, charsetOf(response)));
      } catch (IOException e) {
        throw new ExternalApiException("Could not read that link", e);
      }
    }

    throw new ValidationException("That link redirects too many times");
  }

  private HttpResponse<InputStream> send(URI uri) {
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .GET()
            .timeout(RESPONSE_TIMEOUT)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,image/*;q=0.8,*/*;q=0.5")
            .build();

    try {
      return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException e) {
      throw new ExternalApiException("Could not reach that link", e);
    } catch (InterruptedException e) {
      // Re-flagged rather than swallowed: this runs on the request thread, and a shutdown that
      // interrupts it must not be forgotten by the layers above.
      Thread.currentThread().interrupt();
      throw new ExternalApiException("Interrupted while fetching that link", e);
    }
  }

  private boolean isRedirect(int status) {
    return status >= 300 && status < 400;
  }

  /** The next URL in a redirect chain, resolved against the current one and re-checked. */
  private URI nextHop(URI current, HttpResponse<InputStream> response) {
    String location = response.headers().firstValue("location").orElse(null);
    if (Objects.isNull(location) || location.isBlank()) {
      throw new ExternalApiException("That link redirected to nowhere");
    }
    try {
      return urlSafetyGuard.requireFetchable(current.resolve(location).toString());
    } catch (IllegalArgumentException e) {
      throw new ExternalApiException("That link redirected somewhere unreadable", e);
    }
  }

  private String contentTypeOf(HttpResponse<InputStream> response) {
    return response
        .headers()
        .firstValue("content-type")
        .map(value -> value.toLowerCase(Locale.ROOT).trim())
        .orElse("");
  }

  /**
   * The charset the response declared, defaulting to UTF-8.
   *
   * <p>Only the HTTP header is consulted, not a {@code <meta charset>} inside the document — that
   * would mean decoding the bytes to find out how to decode the bytes. For the fields read here the
   * cost of getting it wrong is mojibake in a title the author can retype, and UTF-8 is right for
   * very nearly everything.
   */
  private Charset charsetOf(HttpResponse<InputStream> response) {
    String contentType = contentTypeOf(response);
    int marker = contentType.indexOf("charset=");
    if (marker < 0) {
      return StandardCharsets.UTF_8;
    }
    String name = contentType.substring(marker + "charset=".length()).trim();
    int end = name.indexOf(';');
    if (end >= 0) {
      name = name.substring(0, end);
    }
    name = name.replace("\"", "").trim();

    try {
      return Charset.forName(name);
    } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
      return StandardCharsets.UTF_8;
    }
  }

  /**
   * Reads at most {@link #MAX_BODY_BYTES}, then stops and decodes what it has.
   *
   * <p>Truncating rather than failing: {@code <head>} comes first in a document, so a page too
   * large to read in full has almost certainly already yielded everything wanted before the cap is
   * reached. The stream is closed by the caller's try-with-resources, which also drops the rest of
   * the transfer rather than politely draining it.
   */
  private String read(InputStream body, Charset charset) throws IOException {
    byte[] bytes = body.readNBytes(MAX_BODY_BYTES);
    return new String(bytes, charset);
  }

  private record Page(URI url, String contentType, String body) {}
}
