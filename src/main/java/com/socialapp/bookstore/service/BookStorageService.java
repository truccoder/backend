package com.socialapp.bookstore.service;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.common.exception.StorageException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.common.utils.FileExtensions;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookStorageService {

  /** Reaches MinIO for real work — here, removing an orphaned upload. Internal address in prod. */
  private final MinioClient minioClient;

  /**
   * Signs display/download URLs only. Built on the <b>public</b> MinIO address so the host inside
   * the signature is the one the browser will use; see {@code MinIOConfig#minioPresignClient()}.
   */
  private final MinioClient minioPresignClient;

  private final com.socialapp.cloud.minio.MinIOService minIOService;

  private static final String BOOKS_BUCKET = "books";
  private static final String COVERS_BUCKET = "book-covers";

  /**
   * How long a signed cover or preview URL stays valid.
   *
   * <p>Generous on purpose: these render on the feed and on search results for every viewer on
   * every page, so a short life would mean re-signing constantly and would break any page a reader
   * left open.
   */
  private static final int DISPLAY_URL_EXPIRY_HOURS = 24;

  /**
   * How long a signed URL for a <em>paid</em> book file stays valid.
   *
   * <p>Deliberately much shorter than {@link #DISPLAY_URL_EXPIRY_HOURS}. A presigned URL is a
   * bearer token: whoever holds the string downloads the file, with no account and no purchase
   * check — {@code BookService#getFullDownloadUrl} verifies the purchase before signing, and after
   * that the string is on its own. Twenty-four hours was long enough for a link to be pasted
   * somewhere public; five minutes is long enough for a browser to start the download it was
   * issued for.
   */
  private static final int DOWNLOAD_URL_EXPIRY_MINUTES = 5;

  public String uploadBook(Integer authorId, MultipartFile file) {
    requireFile(file);
    String extension = FileExtensions.getExtension(file.getOriginalFilename(), "pdf");
    String objectKey = "books/" + authorId + "/" + UUID.randomUUID() + "." + extension;

    // MinIOService throws StorageException itself now, so there is nothing to translate here and
    // no catch wide enough to swallow an unrelated bug by accident.
    minIOService.uploadFile(BOOKS_BUCKET, objectKey, file);
    log.info("Uploaded book file: {}", objectKey);
    return objectKey;
  }

  public String uploadPreview(Integer authorId, byte[] previewBytes, String extension) {
    String objectKey = "previews/" + authorId + "/" + UUID.randomUUID() + "." + extension;
    String contentType = "epub".equals(extension) ? "application/epub+zip" : "application/pdf";

    minIOService.uploadBytes(BOOKS_BUCKET, objectKey, previewBytes, contentType);
    log.info("Uploaded book preview file: {}", objectKey);
    return objectKey;
  }

  /**
   * Uploads a cover and returns its object key.
   *
   * <p>Returning the presigned URL here is what broke covers: the caller persisted that string,
   * and it stopped working 24h later. Every other upload on this class already returns a key —
   * this one is now consistent with them, and signing happens in {@link #getCoverUrl}.
   */
  public String uploadCover(Integer authorId, MultipartFile file) {
    requireFile(file);
    String extension = FileExtensions.getExtension(file.getOriginalFilename(), "pdf");
    String objectKey = "covers/" + authorId + "/" + UUID.randomUUID() + "." + extension;

    minIOService.uploadFile(COVERS_BUCKET, objectKey, file);
    log.info("Uploaded cover image: {}", objectKey);
    return objectKey;
  }

  public String getDownloadUrl(String fileKey) {
    return getPresignedUrl(BOOKS_BUCKET, fileKey, DOWNLOAD_URL_EXPIRY_MINUTES, TimeUnit.MINUTES);
  }

  /** The free sample, so it gets the display lifetime rather than the paid-download one. */
  public String getPreviewUrl(String fileKey) {
    return getPresignedUrl(BOOKS_BUCKET, fileKey, DISPLAY_URL_EXPIRY_HOURS, TimeUnit.HOURS);
  }

  /**
   * Signs a cover key for display, tolerating failure.
   *
   * <p>Unlike download/preview this runs on the newsfeed and search read paths, which render for
   * every viewer on every page. {@code getPresignedUrl} throws when the bucket is missing (an
   * ordinary state on a freshly reset MinIO), and letting that escape would turn a missing cover
   * image into a dead feed. A book with no cover is a card without a picture; a 503 is an app
   * nobody can use.
   */
  public String getCoverUrl(String coverKey) {
    if (Objects.isNull(coverKey) || coverKey.isBlank()) {
      return null;
    }

    try {
      return getPresignedUrl(COVERS_BUCKET, coverKey, DISPLAY_URL_EXPIRY_HOURS, TimeUnit.HOURS);
    } catch (Exception e) {
      log.warn("Failed to sign cover key '{}': {}", coverKey, e.getMessage());
      return null;
    }
  }

  /**
   * Best-effort removal of an object already uploaded during a book creation that then failed.
   *
   * <p>MinIO is not in the surrounding transaction, so when validation or the insert rejects the
   * book, Postgres rolls back and the uploaded files stay behind forever. This never throws: the
   * caller is already unwinding an error and a failed cleanup must not replace it.
   */
  public void deleteQuietly(String bucket, String objectKey) {
    if (Objects.isNull(objectKey) || objectKey.isBlank()) {
      return;
    }

    try {
      minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
      log.info("Removed orphaned object {}/{}", bucket, objectKey);
    } catch (Exception e) {
      log.warn("Failed to remove orphaned object {}/{}: {}", bucket, objectKey, e.getMessage());
    }
  }

  public String booksBucket() {
    return BOOKS_BUCKET;
  }

  public String coversBucket() {
    return COVERS_BUCKET;
  }

  private String getPresignedUrl(String bucket, String objectKey, int expiry, TimeUnit expiryUnit) {
    try {
      return minioPresignClient.getPresignedObjectUrl(
          GetPresignedObjectUrlArgs.builder()
              .method(Method.GET)
              .bucket(bucket)
              .object(objectKey)
              .expiry(expiry, expiryUnit)
              .build());
    } catch (Exception e) {
      throw new StorageException("Failed to generate download URL", e);
    }
  }

  private void requireFile(MultipartFile file) {
    if (file == null) {
      throw new ValidationException("File must not be null");
    }
  }
}
