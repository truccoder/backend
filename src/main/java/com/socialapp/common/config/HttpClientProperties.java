package com.socialapp.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/** Timeouts applied to every outbound {@code WebClient}. See {@link WebClientTimeoutConfig}. */
@ConfigurationProperties(prefix = "http-client")
@Data
public class HttpClientProperties {

  /**
   * How long to wait for a TCP connection to be established.
   *
   * <p>Short on purpose. A host that has not accepted a connection in five seconds is not slow, it
   * is unreachable, and every further second is a request thread held for nothing.
   */
  private Duration connectTimeout = Duration.ofSeconds(5);

  /**
   * How long to wait for the response after the request has been sent.
   *
   * <p>This is the one that matters. Without it, an upstream that accepts a connection and then
   * stops talking holds the calling thread forever — Tomcat's pool drains, and a third party's
   * outage becomes this service's outage.
   */
  private Duration responseTimeout = Duration.ofSeconds(20);

  /**
   * Response timeout for the Gemini calls specifically.
   *
   * <p>Separate because generation genuinely takes longer than an API call: content moderation and
   * the explanation feature ask for up to 8192 output tokens. Holding those to the same 20 seconds
   * as everything else would time out work that was going to succeed, which is worse than waiting —
   * the request is already asynchronous from the user's point of view.
   */
  private Duration aiResponseTimeout = Duration.ofSeconds(60);
}
