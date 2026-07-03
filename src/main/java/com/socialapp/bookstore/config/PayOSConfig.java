package com.socialapp.bookstore.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class PayOSConfig {
  private final PayOSProperties payOSProperties;

  @Bean
  public WebClient payOSWebClient() {
    return WebClient.builder()
        .baseUrl(payOSProperties.getApiUrl())
        .defaultHeader("x-client-id", payOSProperties.getClientId())
        .defaultHeader("x-api-key", payOSProperties.getApiKey())
        .build();
  }
}
