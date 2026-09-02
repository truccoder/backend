package com.socialapp.linkpreview.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The HTTP client {@code LinkPreviewService} uses, and nothing else.
 *
 * <p>Its own bean rather than the shared {@code WebClient} the crawlers use, because the two need
 * opposite settings: this one must never follow a redirect on its own — see {@code
 * LinkPreviewService.MAX_REDIRECTS} — and must give up quickly, since a person is waiting on the
 * other end of it. A client configured that way is not one to hand to the rest of the application
 * by accident, so it is qualified by name.
 */
@Configuration
public class LinkPreviewClientConfig {

  /**
   * Three seconds to establish a connection. Generous for a reachable public host, short enough
   * that a host chosen to hang does not hold a request thread while the person who pasted the link
   * watches a spinner.
   */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

  @Bean
  public HttpClient linkPreviewHttpClient() {
    return HttpClient.newBuilder()
        // NEVER, deliberately: redirects are followed by hand so that the safety guard sees every
        // hop. Letting the client follow them would make the guard's check apply only to the URL
        // the caller typed, which is the one hop an attacker does not need.
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(CONNECT_TIMEOUT)
        .build();
  }
}
