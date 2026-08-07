package com.socialapp.bookstore.service;

import java.io.InputStream;
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
  private final MinioClient minioClient;
  private final com.socialapp.cloud.minio.MinIOService minIOService;

  private static final String BOOKS_BUCKET = "books";
  private static final String COVERS_BUCKET = "book-covers";
  private static final int URL_EXPIRY_HOURS = 24;

  public String uploadBook(Integer authorId, MultipartFile file) {
    requireFile(file);
    String extension = FileExtensions.getExtension(file.getOriginalFilename(), "pdf");
    String objectKey = "books/" + authorId + "/" + UUID.randomUUID() + "." + extension;

    try (InputStream inputStream = file.getInputStream()) {
      minIOService.uploadFile(BOOKS_BUCKET, objectKey, file);
      log.info("Uploaded book file: {}", objectKey);
      return objectKey;
    } catch (Exception e) {
      throw new StorageException("Failed to upload book file", e);
    }
  }

  public String uploadPreview(Integer authorId, byte[] previewBytes, String extension) {
    String objectKey = "previews/" + authorId + "/" + UUID.randomUUID() + "." + extension;
    String contentType = "epub".equals(extension) ? "application/epub+zip" : "application/pdf";

    try {
      minIOService.uploadBytes(BOOKS_BUCKET, objectKey, previewBytes, contentType);
      log.info("Uploaded book preview file: {}", objectKey);
      return objectKey;
    } catch (Exception e) {
      throw new StorageException("Failed to upload book preview file", e);
    }
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

    try {
      minIOService.uploadFile(COVERS_BUCKET, objectKey, file);
      log.info("Uploaded cover image: {}", objectKey);
      return objectKey;
    } catch (Exception e) {
      throw new StorageException("Failed to upload cover image", e);
    }
  }

  public String getDownloadUrl(String fileKey) {
    return getPresignedUrl(BOOKS_BUCKET, fileKey);
  }

  public String getPreviewUrl(String fileKey) {
    return getPresignedUrl(BOOKS_BUCKET, fileKey);
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
      return getPresignedUrl(COVERS_BUCKET, coverKey);
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

  private String getPresignedUrl(String bucket, String objectKey) {
    try {
      return minioClient.getPresignedObjectUrl(
          GetPresignedObjectUrlArgs.builder()
              .method(Method.GET)
              .bucket(bucket)
              .object(objectKey)
              .expiry(URL_EXPIRY_HOURS, TimeUnit.HOURS)
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
