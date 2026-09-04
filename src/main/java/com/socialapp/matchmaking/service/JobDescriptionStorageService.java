package com.socialapp.matchmaking.service;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.socialapp.cloud.minio.MinIOService;
import com.socialapp.common.exception.StorageException;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Where rendered job descriptions live, and how a browser is let at one.
 *
 * <p>The same arrangement as {@code BookStorageService}, and split from it rather than shared with
 * it because the two answer different questions about access. A book file is paid for, so its URL
 * is signed for minutes; a JD is a public posting whose whole purpose is to be opened, forwarded
 * and read, so it gets the display lifetime a book <em>cover</em> gets.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobDescriptionStorageService {

  /** Reaches MinIO for real work — here, deleting a superseded render. Internal address in prod. */
  private final MinioClient minioClient;

  /**
   * Signs the URL a browser opens. Built on the <b>public</b> MinIO address so the host inside the
   * signature is the one the browser can reach; see {@code MinIOConfig#minioPresignClient()}.
   */
  private final MinioClient minioPresignClient;

  private final MinIOService minIOService;

  /**
   * Private, like the book buckets: objects are reached only through a signed URL. Not because a
   * JD is a secret — it is not — but because an unsigned bucket URL is a permanent, unrevokable
   * link to a document whose owner can delete the role behind it.
   */
  public static final String JOB_DESCRIPTIONS_BUCKET = "job-descriptions";

  /**
   * How long a signed JD URL lives. Matches the book <em>cover</em> lifetime rather than the
   * download one: this link is opened from a role card, sometimes minutes after the page loaded,
   * and re-signing on every render of a project page would cost a round trip per role.
   */
  private static final int URL_EXPIRY_HOURS = 24;

  /**
   * Stores a freshly rendered JD and returns its object key.
   *
   * <p>The key carries a UUID rather than being {@code positions/{id}.pdf}: a re-render must not
   * overwrite the object a signed URL already points at, or a reader who opened the link a minute
   * ago gets a document that changed under them mid-scroll. The old key is deleted by the caller
   * once the new one is committed.
   */
  public String upload(Integer projectId, Integer positionId, byte[] pdf) {
    String objectKey =
        "job-descriptions/" + projectId + "/" + positionId + "-" + UUID.randomUUID() + ".pdf";
    minIOService.uploadBytes(JOB_DESCRIPTIONS_BUCKET, objectKey, pdf, "application/pdf");
    log.info("Uploaded job description PDF: {}", objectKey);
    return objectKey;
  }

  public String signedUrl(String objectKey) {
    try {
      return minioPresignClient.getPresignedObjectUrl(
          GetPresignedObjectUrlArgs.builder()
              .method(Method.GET)
              .bucket(JOB_DESCRIPTIONS_BUCKET)
              .object(objectKey)
              .expiry(URL_EXPIRY_HOURS, TimeUnit.HOURS)
              .build());
    } catch (Exception e) {
      throw new StorageException("Failed to generate a job description URL", e);
    }
  }

  /**
   * Removes a render that has been replaced. Never throws: the new PDF is already stored and
   * already answered the request, so failing to tidy the old one must not turn a served response
   * into an error. Same contract as {@code BookStorageService.deleteQuietly}.
   */
  public void deleteQuietly(String objectKey) {
    if (Objects.isNull(objectKey) || objectKey.isBlank()) {
      return;
    }
    try {
      minioClient.removeObject(
          RemoveObjectArgs.builder().bucket(JOB_DESCRIPTIONS_BUCKET).object(objectKey).build());
    } catch (Exception e) {
      log.warn("Failed to remove superseded job description '{}': {}", objectKey, e.getMessage());
    }
  }
}
