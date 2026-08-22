package com.socialapp.common.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Tuning for {@link AuthRateLimitFilter} and for the per-email throttle on the flows that send mail.
 *
 * <p>Deliberately far tighter than {@link GuestRateLimitProperties}. Reading the public feed is
 * something a real visitor does dozens of times a minute; signing in is something they do once, and
 * asking for a password-reset mail is something they do once in a while. A budget sized for
 * browsing gives a password-guesser 60 attempts a minute.
 */
@ConfigurationProperties(prefix = "rate-limit.auth")
@Data
public class AuthRateLimitProperties {

  private boolean enabled = true;

  /**
   * Requests one IP may make to {@code /v1/api/auth/**} inside {@link #window}.
   *
   * <p>Sized to leave a real person alone while making guessing pointless: a mistyped password
   * retried a few times, a token refresh, and an OAuth round trip all fit inside 20 in five
   * minutes. A guesser gets 20 tries per five minutes per address instead of an unlimited stream.
   */
  private int requests = 20;

  private Duration window = Duration.ofMinutes(5);

  /**
   * How many mails one email address may trigger inside {@link #mailWindow}, counted across all
   * source addresses.
   *
   * <p>The IP limit above does not cover this case. Password reset, magic link and verification
   * mails are addressed to a victim who never asked for them, so the abuse worth stopping is many
   * addresses each politely staying under the IP budget while one inbox is buried. Counting per
   * recipient is the only key that sees that.
   */
  private int mailRequests = 3;

  private Duration mailWindow = Duration.ofMinutes(15);

  /**
   * Paths the IP limit applies to.
   *
   * <p>The whole auth tree, including the OAuth callbacks — an attacker replaying stolen
   * authorization codes belongs under the same budget as one guessing passwords.
   */
  private String pathPattern = "/v1/api/auth/**";

  /**
   * Whether to read the client IP from {@code X-Forwarded-For} instead of the socket address.
   *
   * <p>Same rule, and same danger, as {@link GuestRateLimitProperties#isTrustForwardedFor()}:
   * anyone can put whatever they like in that header, so trusting it without a reverse proxy in
   * front means a guesser sends a fresh fake IP on every attempt and the limit protects nothing.
   * On this deployment Caddy overwrites the header and the backend publishes no port of its own,
   * which is what makes turning it on safe there.
   */
  private boolean trustForwardedFor = false;
}
