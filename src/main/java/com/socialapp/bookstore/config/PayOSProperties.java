package com.socialapp.bookstore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "payos")
public class PayOSProperties {
  private String clientId;
  private String apiKey;
  private String checksumKey;
  private String apiUrl = "https://api-merchant.payos.vn";
  private String returnUrl;
  private String cancelUrl;
}
