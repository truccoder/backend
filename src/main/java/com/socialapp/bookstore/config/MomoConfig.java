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
  public WebClient momoWebClient(WebClient.Builder builder) {
    // Builder được inject, không phải WebClient.builder() tĩnh: chỉ bản được Spring quản lý
    // mới nhận WebClientCustomizer, tức là mới có timeout (xem WebClientTimeoutConfig).
    return builder.baseUrl(momoProperties.getApiUrl()).build();
  }
}
