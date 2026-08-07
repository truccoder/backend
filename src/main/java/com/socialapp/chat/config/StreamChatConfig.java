package com.socialapp.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class StreamChatConfig {

  @Bean
  public WebClient streamChatWebClient(StreamChatProperties properties) {
    return WebClient.builder().baseUrl(properties.getBaseUrl()).build();
  }
}
