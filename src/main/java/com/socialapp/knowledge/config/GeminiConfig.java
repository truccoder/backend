package com.socialapp.knowledge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.socialapp.common.config.HttpClientProperties;
import com.socialapp.common.config.WebClientTimeoutConfig;

@Configuration
public class GeminiConfig {
  @Bean
  public WebClient geminiWebClient(
      GeminiProperties properties,
      WebClient.Builder builder,
      HttpClientProperties httpClientProperties) {
    return builder
        .baseUrl(properties.getBaseUrl())
        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
        // Ghi de connector mac dinh bang mot cai co response timeout dai hon.
        //
        // Gemini sinh toi 8192 token dau ra, viec do that su lau hon mot loi goi API thong thuong,
        // nen muc 20 giay dung chung se cat ngang nhung yeu cau von se thanh cong. Connect timeout
        // giu nguyen: mot host chua bat tay duoc sau 5 giay thi khong phai cham, ma la khong toi
        // duoc — cho them cung khong giup gi.
        .clientConnector(
            WebClientTimeoutConfig.connector(
                httpClientProperties.getConnectTimeout(),
                httpClientProperties.getAiResponseTimeout()))
        .build();
  }
}
