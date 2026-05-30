package com.socialapp.moderation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class ModerationConfig {
  @Bean
  public WebClient perspectiveApiWebClient() {
    return WebClient.builder().baseUrl("https://commentanalyzer.googleapis.com/v1alpha1").build();
  }

  @Bean
  public WebClient cloudVisionWebClient() {
    return WebClient.builder().baseUrl("https://vision.googleapis.com/v1").build();
  }
}
