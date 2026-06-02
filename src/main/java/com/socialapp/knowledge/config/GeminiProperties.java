package com.socialapp.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "gemini")
public class GeminiProperties {
  private String apiKey;
  private String model = "gemini-2.0-flash";
  private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";
  private int maxOutputTokens = 8192;
  private double temperature = 0.7;
}
