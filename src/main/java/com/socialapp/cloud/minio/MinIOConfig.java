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
   * Public base address of the object store, e.g. {@code https://files.example.com}. It is the
   * prefix baked into {@code t_users.profile_picture_url}, {@code t_users.cover_image_url} and
   * {@code t_posts.images} — {@code ProfileService} and {@code MediaService} build those as
   * {@code getUrl() + "/" + bucket + "/" + key} — and it is the host that must appear inside the
   * signature of every presigned book URL (see {@link #minioPresignClient()}).
   *
   * <p>Any trailing slash is stripped on the way in. If the configured value ended in {@code /}
   * the concatenation above would produce {@code //} right after the host; MinIO answers such a
   * path with HTTP 400 and a browser sends it verbatim, so every affected image renders broken.
   * This hit production on 2026-08-31 when the {@code MINIO_PUBLIC_URL} secret was set to
   * {@code https://files.elitenexus.id.vn/}. Migration {@code V102} repairs rows written before
   * this guard existed.
   */
  private String url;

  /**
   * Address the application uses to <b>reach</b> MinIO: every upload, stat, list, bucket create and
   * the public-read policy call. Defaults to {@link #url} when left unset, which is what dev wants.
   *
   * <p><b>Set this in production to the Docker-network address</b> ({@code http://minio:9000}).
   * When it was left as the public URL, {@code MinIOBucketInitializer}'s policy call and
   * {@code MinIOSeedObjectInitializer}'s uploads all went through the public reverse proxy — and on
   * first boot, before Caddy was ready, the policy call failed (logged, never retried), leaving
   * {@code profile-pictures} and {@code post-media} private so every seeded avatar and post image
   * came back 403. An internal address removes that ordering dependency and is faster besides.
   *
   * <p>Presigned URLs are deliberately <i>not</i> signed with this address — see
   * {@link #minioPresignClient()}.
   */
  private String internalUrl;

  private String accessKey;
  private String secretKey;

  public void setUrl(String url) {
    this.url = strip(url);
  }

  public void setInternalUrl(String internalUrl) {
    this.internalUrl = strip(internalUrl);
  }

  /** The address the app connects to. Falls back to the public URL when not separately configured. */
  public String getInternalUrl() {
    return internalUrl == null || internalUrl.isBlank() ? url : internalUrl;
  }

  private static String strip(String value) {
    return value == null ? null : value.replaceAll("/+$", "");
  }

  /**
   * Client for every S3 operation the application performs itself: upload, stat, list, bucket
   * admin. Uses {@link #getInternalUrl()} so these never depend on the public reverse proxy.
   */
  @Bean
  public MinioClient minioClient() {
    return MinioClient.builder()
        .endpoint(getInternalUrl())
        .credentials(accessKey, secretKey)
        .build();
  }

  /**
   * Client used <b>only</b> to sign URLs handed to browsers (book covers, previews, downloads).
   * Signing is offline computation — this client never opens a connection — so it exists purely to
   * put the public host ({@link #url}) into the SigV4 signature. Swapping the host on an
   * already-signed URL would break the signature, which is why a second client is needed rather
   * than a string replace. When internal and public URLs are the same this is equivalent to
   * {@link #minioClient()}.
   */
  @Bean
  public MinioClient minioPresignClient() {
    return MinioClient.builder().endpoint(url).credentials(accessKey, secretKey).build();
  }
}
