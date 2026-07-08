package com.socialapp.moderation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.socialapp.moderation.enums.Likelihood;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "moderation")
public class ModerationProperties {
  private boolean enabled;
  private TextModeration textModeration = new TextModeration();
  private CloudVision cloudVision = new CloudVision();
  private Rules rules = new Rules();

  /**
   * Thresholds for the Gemini-backed text scoring in {@link com.socialapp.moderation.ai.TextModerationService}.
   */
  @Data
  public static class TextModeration {
    private double toxicityThreshold = 0.7;
    private double reviewThreshold = 0.5;
  }

  @Data
  public static class CloudVision {
    private boolean enabled = false;
    private String apiKey;
    private Likelihood rejectLikelihood = Likelihood.LIKELY;
    private Likelihood reviewLikelihood = Likelihood.POSSIBLE;
  }

  @Data
  public static class Rules {
    private String keywordBlacklistPath = "classpath:moderation/blacklist.txt";
  }
}
