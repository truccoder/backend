package com.socialapp.cloud.minio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.common.exception.StorageException;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.SetBucketPolicyArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.ServerException;
import io.minio.messages.ErrorResponse;
import io.minio.messages.Item;

/**
 * Component (unit) tests for {@link MinIOService}, per ISTQB CTFL v4.0.1 (Section 2.2.1 component
 * testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3 BDD
 * Given/When/Then) — see {@code PostServiceTest} for the full rationale. {@link MinioClient} is a
 * concrete class (not final), so it's mocked directly like any other collaborator — no real MinIO
 * server is ever contacted.
 */
@ExtendWith(MockitoExtension.class)
class MinIOServiceTest {

  private static final String BUCKET = "test-bucket";
  private static final String OBJECT = "test-object.png";

  @Mock private MinioClient minioClient;

  @InjectMocks private MinIOService minIOService;

  @Captor private ArgumentCaptor<PutObjectArgs> putObjectCaptor;
  @Captor private ArgumentCaptor<SetBucketPolicyArgs> policyCaptor;

  private static MultipartFile file(byte[] content, String contentType) throws IOException {
    MultipartFile file = mock(MultipartFile.class);
    when(file.getInputStream()).thenReturn(new ByteArrayInputStream(content));
    when(file.getSize()).thenReturn((long) content.length);
    when(file.getContentType()).thenReturn(contentType);
    return file;
  }

  // =====================================================================
  // uploadFile — exercises ensureBucketExists too
  // =====================================================================

  @Nested
  @DisplayName("uploadFile")
  class UploadFileTests {

    @Test
    @DisplayName("should create the bucket when it does not already exist")
    void shouldCreateBucket_whenBucketDoesNotExist() throws Exception {
      // Given
      when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
      MultipartFile file = file(new byte[] {1, 2, 3}, "image/png");

      // When
      String result = minIOService.uploadFile(BUCKET, OBJECT, file);

      // Then
      assertThat(result).isEqualTo(OBJECT);
      verify(minioClient).makeBucket(any());
      verify(minioClient).putObject(putObjectCaptor.capture());
      assertThat(putObjectCaptor.getValue().bucket()).isEqualTo(BUCKET);
      assertThat(putObjectCaptor.getValue().object()).isEqualTo(OBJECT);
    }

    @Test
    @DisplayName("should skip bucket creation when it already exists")
    void shouldSkipBucketCreation_whenBucketAlreadyExists() throws Exception {
      // Given
      when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
      MultipartFile file = file(new byte[] {1, 2, 3}, "image/png");

      // When
      minIOService.uploadFile(BUCKET, OBJECT, file);

      // Then
      verify(minioClient, never()).makeBucket(any());
      verify(minioClient).putObject(any());
    }
  }

  // =====================================================================
  // uploadBytes
  // =====================================================================

  @Nested
  @DisplayName("uploadBytes")
  class UploadBytesTests {

    @Test
    @DisplayName("should upload the given bytes and return the object name")
    void shouldUploadBytesAndReturnObjectName() throws Exception {
      // Given
      when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

      // When
      String result =
          minIOService.uploadBytes(BUCKET, OBJECT, new byte[] {4, 5, 6}, "application/pdf");

      // Then
      assertThat(result).isEqualTo(OBJECT);
      verify(minioClient).putObject(putObjectCaptor.capture());
      assertThat(putObjectCaptor.getValue().bucket()).isEqualTo(BUCKET);
      verify(minioClient, never()).makeBucket(any());
    }
  }

  // =====================================================================
  // objectExists
  // =====================================================================

  @Nested
  @DisplayName("objectExists")
  class ObjectExistsTests {

    @Test
    @DisplayName("should return true when statObject succeeds")
    void shouldReturnTrue_whenObjectIsThere() throws Exception {
      when(minioClient.statObject(any(StatObjectArgs.class)))
          .thenReturn(mock(StatObjectResponse.class));

      assertThat(minIOService.objectExists(BUCKET, OBJECT)).isTrue();
    }

    @Test
    @DisplayName("should return false when MinIO answers NoSuchKey")
    void shouldReturnFalse_whenObjectIsAbsent() throws Exception {
      ErrorResponse notFound =
          new ErrorResponse(
              "NoSuchKey", "The specified key does not exist.", BUCKET, OBJECT, null, null, null);
      when(minioClient.statObject(any(StatObjectArgs.class)))
          .thenThrow(new ErrorResponseException(notFound, null, null));

      assertThat(minIOService.objectExists(BUCKET, OBJECT)).isFalse();
    }

    @Test
    @DisplayName("should raise StorageException when the stat call itself fails")
    void shouldThrowStorageException_whenStatFails() throws Exception {
      when(minioClient.statObject(any(StatObjectArgs.class)))
          .thenThrow(new ServerException("Internal error", 500, "trace-id"));

      assertThatThrownBy(() -> minIOService.objectExists(BUCKET, OBJECT))
          .isInstanceOf(StorageException.class);
    }
  }

  // =====================================================================
  // ensurePublicReadPolicy
  // =====================================================================

  @Nested
  @DisplayName("ensurePublicReadPolicy")
  class EnsurePublicReadPolicyTests {

    @Test
    @DisplayName("should set a bucket policy referencing the bucket name")
    void shouldSetPolicy_containingBucketName() throws Exception {
      // When
      minIOService.ensurePublicReadPolicy(BUCKET);

      // Then
      verify(minioClient).setBucketPolicy(policyCaptor.capture());
      assertThat(policyCaptor.getValue().bucket()).isEqualTo(BUCKET);
      assertThat(policyCaptor.getValue().config()).contains("arn:aws:s3:::" + BUCKET + "/*");
    }
  }

  // =====================================================================
  // listAllFiles
  // =====================================================================

  @Nested
  @DisplayName("listAllFiles")
  class ListAllFilesTests {

    @Test
    @DisplayName("should return an empty list when the bucket has no objects")
    void shouldReturnEmptyList_whenNoResults() throws Exception {
      // Given
      when(minioClient.listObjects(any())).thenReturn(List.of());

      // When / Then
      assertThat(minIOService.listAllFiles(BUCKET)).isEmpty();
    }

    @Test
    @DisplayName("should return every object's name when the bucket has objects")
    void shouldReturnObjectNames_whenResultsExist() throws Exception {
      // Given
      Item item1 = mock(Item.class);
      when(item1.objectName()).thenReturn("a.png");
      Item item2 = mock(Item.class);
      when(item2.objectName()).thenReturn("b.png");
      when(minioClient.listObjects(any()))
          .thenReturn(List.of(new Result<>(item1), new Result<>(item2)));

      // When
      List<String> result = minIOService.listAllFiles(BUCKET);

      // Then
      assertThat(result).containsExactly("a.png", "b.png");
    }
  }
}
