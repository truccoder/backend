package com.socialapp.notifications.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "onesignal")
public class OneSignalProperties {
  private String appId;
  private String restApiKey;
  private String baseUrl = "https://onesignal.com/api/v1";
}
