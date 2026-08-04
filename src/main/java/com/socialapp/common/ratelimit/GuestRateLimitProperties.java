package com.socialapp.common.ratelimit;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/** Tuning for {@link GuestRateLimitFilter}. */
@ConfigurationProperties(prefix = "rate-limit.guest")
@Data
public class GuestRateLimitProperties {

  private boolean enabled = true;

  /** Requests one IP may make to the guest-readable endpoints inside one {@link #window}. */
  private int requests = 60;

  private Duration window = Duration.ofMinutes(1);

  /**
   * Paths the limit applies to — the endpoints {@code SecurityConfig} opens to guests. Ant
   * patterns, matched against the request URI.
   *
   * <p>Listed rather than derived from the security config: the two lists genuinely have to agree,
   * and a mismatch is visible when they sit side by side in review, whereas a clever derivation
   * would hide which endpoints are actually protected.
   */
  private List<String> paths =
      List.of(
          "/v1/api/posts/public",
          "/v1/api/posts/*",
          "/v1/api/users/*/profile",
          "/v1/api/users/*/posts",
          "/v1/api/users/*/reputation",
          "/v1/api/github/stats/*",
          "/v1/api/books/author/*",
          "/v1/api/trending");

  /**
   * Whether to read the client IP from {@code X-Forwarded-For} instead of the socket address.
   *
   * <p><b>Off by default, and that default is the safe one.</b> Anyone can put whatever they like
   * in that header, so trusting it without a proxy in front means the limit is bypassed by sending
   * a new fake IP on every request — the rate limiter would then cost work and protect nothing.
   * Turn it on only when this service sits behind a reverse proxy that overwrites the header.
   */
  private boolean trustForwardedFor = false;
}
