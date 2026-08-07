package com.socialapp.chat.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "stream.chat")
public class StreamChatProperties {

  /**
   * Public Stream application key. Ships to the browser inside every chat request, so it is handed
   * back to the frontend in the token response rather than duplicated in a frontend env var — two
   * sources of truth for the same value drift silently and only break in production.
   */
  private String apiKey;

  /** Server-side signing secret. Never leaves the backend. */
  private String apiSecret;

  private String baseUrl = "https://chat.stream-io-api.com";

  /** How long an issued user token stays valid. */
  private Duration tokenTtl = Duration.ofHours(24);

  public boolean isConfigured() {
    return StringUtils.hasText(apiKey) && StringUtils.hasText(apiSecret);
  }
}
