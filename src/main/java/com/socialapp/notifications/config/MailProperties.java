package com.socialapp.notifications.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "mail")
public class MailProperties {
  private String fromEmail = "noreply@elitenexus.dev";
  private String fromName = "EliteNexus";
}
