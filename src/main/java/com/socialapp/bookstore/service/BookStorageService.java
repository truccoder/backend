package com.socialapp.bookstore.service;

import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.common.exception.StorageException;
import com.socialapp.common.exception.ValidationException;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
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
    String extension = getExtension(file.getOriginalFilename());
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

  public String uploadCover(Integer authorId, MultipartFile file) {
    requireFile(file);
    String extension = getExtension(file.getOriginalFilename());
    String objectKey = "covers/" + authorId + "/" + UUID.randomUUID() + "." + extension;

    try {
      minIOService.uploadFile(COVERS_BUCKET, objectKey, file);
      log.info("Uploaded cover image: {}", objectKey);
      return getPresignedUrl(COVERS_BUCKET, objectKey);
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
      log.error("Failed to generate presigned URL for {}/{}", bucket, objectKey, e);
      throw new StorageException("Failed to generate download URL", e);
    }
  }

  private void requireFile(MultipartFile file) {
    if (file == null) {
      throw new ValidationException("File must not be null");
    }
  }

  private String getExtension(String filename) {
    if (filename == null || !filename.contains(".")) return "pdf";
    return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
  }
}
