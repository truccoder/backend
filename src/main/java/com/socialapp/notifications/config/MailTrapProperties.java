package com.socialapp.notifications.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "mailtrap")
public class MailTrapProperties {
  private String host;
  private int port;
  private String username;
  private String password;
  private String fromEmail = "noreply@socialapp.dev";
  private String fromName = "SocialApp";
}
