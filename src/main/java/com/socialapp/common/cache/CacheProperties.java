package com.socialapp.common.cache;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@ConfigurationProperties(prefix = "cache")
@Data
public class CacheProperties {
  private Duration defaultTtl = Duration.ofMinutes(30);
  private int jitterPercent = 10;
  private Duration lockTtl = Duration.ofSeconds(5);
  private Duration lockRetryDelay = Duration.ofMillis(100);
  private int lockMaxRetries = 50;
}
