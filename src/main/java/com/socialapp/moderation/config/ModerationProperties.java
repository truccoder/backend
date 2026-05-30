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
  private PerspectiveApi perspectiveApi = new PerspectiveApi();
  private CloudVision cloudVision = new CloudVision();
  private Rules rules = new Rules();

  @Data
  public static class PerspectiveApi {
    private String key;
    private double toxicityThreshold = 0.7;
    private double reviewThreshold = 0.5;
  }

  @Data
  public static class CloudVision {
    private String credentialsPath;
    private Likelihood rejectLikelihood = Likelihood.LIKELY;
    private Likelihood reviewLikelihood = Likelihood.POSSIBLE;
  }

  @Data
  public static class Rules {
    private String keywordBlacklistPath = "classpath:moderation/blacklist.txt";
    private int maxUrlsPerPost = 3;
    private int duplicateWindowSeconds = 60;
  }
}
