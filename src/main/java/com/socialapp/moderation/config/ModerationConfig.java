package com.socialapp.moderation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class ModerationConfig {
  @Bean
  public WebClient cloudVisionWebClient() {
    return WebClient.builder().baseUrl("https://vision.googleapis.com/v1").build();
  }
}
