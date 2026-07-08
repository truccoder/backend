package com.socialapp.bookstore.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class MomoConfig {
  private final MomoProperties momoProperties;

  @Bean
  public WebClient momoWebClient() {
    return WebClient.builder().baseUrl(momoProperties.getApiUrl()).build();
  }
}
