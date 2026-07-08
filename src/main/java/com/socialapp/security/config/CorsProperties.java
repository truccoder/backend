package com.socialapp.security.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "cors")
public class CorsProperties {
  private List<String> allowedOrigins = List.of("http://localhost:3000");
}
