package com.socialapp.common.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Budgets for the endpoints that cost real money or real outbound work per call.
 *
 * <p>Keyed on the <b>user id</b>, which is the gap the other two limiters leave.
 * {@code AuthRateLimitFilter} covers {@code /v1/api/auth/**} for everybody, and
 * {@code GuestRateLimitFilter} covers nine public read paths but opts out the moment a caller is
 * signed in. Between them, a signed-in user had no ceiling anywhere — including on
 * {@code POST /v1/api/knowledge/posts/{id}/explain}, which spends a Gemini call with no cache and
 * no de-duplication.
 *
 * <p>That endpoint shares its API key with {@code TextModerationService}, so an account looping it
 * does not merely run up a bill: exhausting the quota takes content moderation down with it, and
 * every post then falls to PENDING_REVIEW for a human who is not there.
 */
@Data
@Component
@ConfigurationProperties(prefix = "rate-limit.costly")
public class CostlyOperationProperties {

  private boolean enabled = true;

  /**
   * AI explanations one user may request per {@link #aiWindow}.
   *
   * <p>Sized to leave real study alone — reading a dense post and asking for a re-explanation a few
   * times fits easily — while making a loop pointless.
   */
  private int aiRequests = 20;

  private Duration aiWindow = Duration.ofHours(1);
}
