package com.socialapp.security.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@ConfigurationProperties(prefix = "jwt")
@Validated
@Data
public class JwtProperties {

  @NotBlank
  @Size(min = 32)
  private String secret = "defaultSecretKeyThatShouldBeChangedInProduction12345";

  @Min(1)
  private long accessTokenExpirationMs = 900_000;

  @Min(1)
  private long refreshTokenExpirationMs = 604_800_000;

  public Duration accessTokenTtl() {
    return Duration.ofMillis(accessTokenExpirationMs);
  }

  public Duration refreshTokenTtl() {
    return Duration.ofMillis(refreshTokenExpirationMs);
  }
}
