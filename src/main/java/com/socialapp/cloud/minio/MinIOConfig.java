package com.socialapp.cloud.minio;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.minio.MinioClient;
import lombok.Data;

@Configuration
@ConfigurationProperties(prefix = "minio")
@Data
public class MinIOConfig {
  private String url;
  private String accessKey;
  private String secretKey;

  @Bean
  public MinioClient minioClient() {
    return MinioClient.builder().endpoint(url).credentials(accessKey, secretKey).build();
  }
}
