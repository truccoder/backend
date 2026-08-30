package com.socialapp.moderation.config;

import java.util.Arrays;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.socialapp.common.exception.MissingConfigurationException;
import com.socialapp.knowledge.config.GeminiProperties;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Refuses to let content moderation look switched on while it is not.
 *
 * <p>{@code moderation.enabled} is guarded carefully — it is an environment variable, and
 * {@code application-prod.yml} pins it to {@code true} a second time so a mistyped variable at the
 * compose layer cannot turn filtering off. But that flag only opens the pipeline; whether anything
 * inside it actually inspects a post depends on two more things, and both of them used to fail in
 * silence and in the permissive direction:
 *
 * <ul>
 *   <li><b>{@code gemini.api-key} unset.</b> {@code TextModerationService} logs a warning and
 *       returns an empty {@code ModerationScores}. Its fields are primitive {@code double}, so
 *       "no signal" is 0.0 — below every threshold — and {@code ModerationDecisionEngine} approves.
 *       An unset key therefore reads exactly like content that scored perfectly clean.
 *   <li><b>{@code moderation.cloud-vision.enabled} false.</b> Handled at the call site: see the
 *       warning in {@code ImageModerationService#analyzeImages}, which used to be DEBUG and so
 *       never appeared in production at all.
 * </ul>
 *
 * <p><b>Fatal under the {@code prod} profile, loud everywhere else.</b> Production is where the
 * gap between "operator believes posts are filtered" and "nothing reads them" does damage, and
 * {@code MissingConfigurationException} is the type this codebase already uses for a required
 * secret that must not be papered over with a fallback — {@code StreamTokenSigner} throws it for
 * the same reason. A developer running locally without a Gemini key is a normal situation and gets
 * an error in the log instead, with the two ways out named.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationConfigurationCheck {

  private static final String PROD_PROFILE = "prod";

  private final ModerationProperties moderationProperties;
  private final GeminiProperties geminiProperties;
  private final Environment environment;

  @PostConstruct
  public void verifyModerationIsActuallyOn() {
    if (!moderationProperties.isEnabled()) {
      log.warn(
          "Content moderation is DISABLED (moderation.enabled=false); posts reach the feed"
              + " unfiltered");
      return;
    }

    if (!moderationProperties.getCloudVision().isEnabled()) {
      log.warn(
          "Content moderation is on, but IMAGE moderation is disabled"
              + " (moderation.cloud-vision.enabled=false); images on posts are not checked");
    }

    String apiKey = geminiProperties.getApiKey();
    if (apiKey != null && !apiKey.isBlank()) {
      return;
    }

    String message =
        "Content moderation is enabled (moderation.enabled=true) but gemini.api-key is not set, so"
            + " text moderation silently approves everything. Set GEMINI_API_KEY, or set"
            + " MODERATION_ENABLED=false to make the fact that content is unfiltered explicit.";

    if (isProduction()) {
      throw new MissingConfigurationException(message);
    }
    log.error(message);
  }

  private boolean isProduction() {
    return Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE);
  }
}
