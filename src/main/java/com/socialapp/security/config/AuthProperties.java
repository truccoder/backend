package com.socialapp.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {
  private String resetPasswordUrl = "http://localhost:3000/forgot-password?token=";
}
