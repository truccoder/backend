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

  /**
   * Base address of the object store, e.g. {@code https://files.example.com}. It doubles as the
   * prefix for the absolute URLs baked into {@code t_users.profile_picture_url},
   * {@code t_users.cover_image_url} and {@code t_posts.images} — {@code ProfileService} and
   * {@code MediaService} build those as {@code getUrl() + "/" + bucket + "/" + key}.
   *
   * <p>Any trailing slash is stripped on the way in. If the configured value ended in {@code /}
   * the concatenation above would produce {@code //} right after the host; MinIO answers such a
   * path with HTTP 400 and a browser sends it verbatim, so every affected image renders broken.
   * This hit production on 2026-08-31 when the {@code MINIO_PUBLIC_URL} secret was set to
   * {@code https://files.elitenexus.id.vn/}. Migration {@code V102} repairs rows written before
   * this guard existed.
   */
  private String url;

  private String accessKey;
  private String secretKey;

  public void setUrl(String url) {
    this.url = url == null ? null : url.replaceAll("/+$", "");
  }

  @Bean
  public MinioClient minioClient() {
    return MinioClient.builder().endpoint(url).credentials(accessKey, secretKey).build();
  }
}
