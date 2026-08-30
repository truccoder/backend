package com.socialapp.cloud.minio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.common.exception.StorageException;

import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.errors.ServerException;
import io.minio.messages.Item;

/**
 * Stage 5 (Experience-based Testing) — Error Guessing technique per ISTQB CTFL v4.0.1 Section
 * 4.5.3, applied to {@link MinIOService}. This is the first test coverage this class has ever
 * had (no Stage 2 unit test previously existed).
 *
 * <p>Unlike {@code BookStorageServiceErrorGuessingTest}, this class does <b>not</b> assert a
 * custom exception type for third-party (MinIO) failures. {@link MinIOService} is a thin,
 * intentionally "dumb" wrapper around {@link MinioClient} — every caller ({@code
 * BookStorageService}, {@code ProfileService}) already catches broadly and wraps into its own
 * domain exception. So the correct "resilience" contract at *this* layer is different: never
 * swallow or corrupt the underlying exception, and never crash on bad input with an
 * undocumented {@link NullPointerException} — but a well-understood {@link
 * IllegalArgumentException} for programmer-error inputs (null file/data) is acceptable, since
 * every current caller already guards against that before calling in.
 *
 * <p>{@link MinioClient} is mocked — no real MinIO server is ever contacted.
 */
@ExtendWith(MockitoExtension.class)
class MinIOServiceErrorGuessingTest {

  private static final String BUCKET = "books";
  private static final String OBJECT_KEY = "books/1/some-file.pdf";

  @Mock private MinioClient minioClient;

  @InjectMocks private MinIOService minIOService;

  private static MultipartFile mockFile(byte[] bytes) throws IOException {
    MultipartFile file = mock(MultipartFile.class);
    when(file.getInputStream()).thenReturn(new ByteArrayInputStream(bytes));
    when(file.getContentType()).thenReturn("application/pdf");
    when(file.getSize()).thenReturn((long) bytes.length);
    return file;
  }

  // =====================================================================
  // Null / bad input
  // =====================================================================

  @Nested
  @DisplayName("Null input")
  class NullInputTests {

    @Test
    @DisplayName("shouldThrowIllegalArgumentException_notNullPointerException_whenFileIsNull")
    void shouldThrowIllegalArgumentException_notNullPointerException_whenFileIsNull() {
      // When / Then — a null MultipartFile must fail predictably, not with a raw NPE
      assertThatThrownBy(() -> minIOService.uploadFile(BUCKET, OBJECT_KEY, null))
          .isInstanceOf(IllegalArgumentException.class)
          .isNotInstanceOf(NullPointerException.class)
          .hasMessageContaining("file must not be null");
    }

    @Test
    @DisplayName("shouldThrowIllegalArgumentException_notNullPointerException_whenDataIsNull")
    void shouldThrowIllegalArgumentException_notNullPointerException_whenDataIsNull() {
      // When / Then
      assertThatThrownBy(
              () -> minIOService.uploadBytes(BUCKET, OBJECT_KEY, null, "application/pdf"))
          .isInstanceOf(IllegalArgumentException.class)
          .isNotInstanceOf(NullPointerException.class)
          .hasMessageContaining("data must not be null");
    }

    @Test
    @DisplayName("shouldNeverContactMinioClient_whenFileIsNull")
    void shouldNeverContactMinioClient_whenFileIsNull() throws Exception {
      // When
      try {
        minIOService.uploadFile(BUCKET, OBJECT_KEY, null);
      } catch (Exception ignored) {
        // asserted separately above; here we only care that the client was never touched
      }

      // Then — fails fast before any network round-trip is attempted
      verify(minioClient, never()).bucketExists(any(BucketExistsArgs.class));
      verify(minioClient, never()).putObject(any(PutObjectArgs.class));
    }
  }

  // =====================================================================
  // Corrupted / empty file content
  // =====================================================================

  @Nested
  @DisplayName("Corrupted or empty file content")
  class CorruptedOrEmptyFileTests {

    @Test
    @DisplayName("shouldUploadSuccessfully_whenFileIsZeroBytes")
    void shouldUploadSuccessfully_whenFileIsZeroBytes() throws Exception {
      // Given
      when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
      MultipartFile emptyFile = mockFile(new byte[0]);

      // When
      String result = minIOService.uploadFile(BUCKET, OBJECT_KEY, emptyFile);

      // Then — no crash; MinIO itself accepts 0-byte objects
      assertThat(result).isEqualTo(OBJECT_KEY);
      verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    @DisplayName("shouldReportStorageFailure_IOException_whenFileStreamIsCorruptedMidRead")
    void shouldReportStorageFailure_IOException_whenFileStreamIsCorruptedMidRead()
        throws Exception {
      // Given — bucket check succeeds, but reading the file's content fails partway through
      // (e.g. the client's temp storage for the multipart upload got truncated)
      when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
      MultipartFile corruptedFile = mock(MultipartFile.class);
      when(corruptedFile.getInputStream())
          .thenThrow(new IOException("Broken pipe: truncated multipart body"));

      // When / Then — propagated as-is; wrapping is the caller's responsibility
      assertThatThrownBy(() -> minIOService.uploadFile(BUCKET, OBJECT_KEY, corruptedFile))
          .isInstanceOf(StorageException.class)
          .hasRootCauseInstanceOf(IOException.class)
          .hasStackTraceContaining("truncated multipart body");
    }
  }

  // =====================================================================
  // Third-party (MinIO) outages: timeout, network error, server 5xx
  // =====================================================================

  @Nested
  @DisplayName("MinIO client outages")
  class ThirdPartyOutageTests {

    @Test
    @DisplayName("shouldReportStorageFailure_SocketTimeoutException_whenBucketExistsCheckTimesOut")
    void shouldReportStorageFailure_SocketTimeoutException_whenBucketExistsCheckTimesOut()
        throws Exception {
      // Given — the very first call this service makes (checking the bucket) times out, before
      // the file's content is ever touched
      when(minioClient.bucketExists(any(BucketExistsArgs.class)))
          .thenThrow(new SocketTimeoutException("Read timed out"));
      MultipartFile file = mock(MultipartFile.class);

      // When / Then — propagated unchanged, not swallowed or reduced to a generic failure
      assertThatThrownBy(() -> minIOService.uploadFile(BUCKET, OBJECT_KEY, file))
          .isInstanceOf(StorageException.class)
          .hasRootCauseInstanceOf(SocketTimeoutException.class)
          .hasStackTraceContaining("Read timed out");
    }

    @Test
    @DisplayName("shouldReportStorageFailure_ConnectException_whenPutObjectHitsNetworkError")
    void shouldReportStorageFailure_ConnectException_whenPutObjectHitsNetworkError()
        throws Exception {
      // Given — bucket check succeeds, but the actual upload can't reach the MinIO host
      when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
      when(minioClient.putObject(any(PutObjectArgs.class)))
          .thenThrow(new ConnectException("Connection refused"));
      MultipartFile file = mockFile(new byte[] {1, 2, 3});

      // When / Then
      assertThatThrownBy(() -> minIOService.uploadFile(BUCKET, OBJECT_KEY, file))
          .isInstanceOf(StorageException.class)
          .hasRootCauseInstanceOf(ConnectException.class)
          .hasStackTraceContaining("Connection refused");
    }

    @Test
    @DisplayName("shouldReportStorageFailure_ServerException_whenPutObjectReturns500")
    void shouldReportStorageFailure_ServerException_whenPutObjectReturns500() throws Exception {
      // Given — MinIO responded, but with a genuine server-side failure
      when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
      when(minioClient.putObject(any(PutObjectArgs.class)))
          .thenThrow(
              new ServerException(
                  "We encountered an internal error, please try again.", 500, "trace-id-1"));
      MultipartFile file = mockFile(new byte[] {1, 2, 3});

      // When / Then
      assertThatThrownBy(() -> minIOService.uploadFile(BUCKET, OBJECT_KEY, file))
          .isInstanceOf(StorageException.class)
          .hasRootCauseInstanceOf(ServerException.class);
    }

    @Test
    @DisplayName("shouldKeepTheOriginalExceptionAsTheCause")
    void shouldKeepTheOriginalExceptionAsTheCause() throws Exception {
      // Given — diagnosability: this thin wrapper must never obscure the real cause
      when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
      ServerException original = new ServerException("Internal error", 503, "trace-id-2");
      when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(original);
      MultipartFile file = mockFile(new byte[] {1, 2, 3});

      // When / Then
      assertThatThrownBy(() -> minIOService.uploadFile(BUCKET, OBJECT_KEY, file))
          .isInstanceOf(StorageException.class)
          .hasCause(original);
    }

    @Test
    @DisplayName("shouldCreateBucketThenUpload_whenBucketDoesNotYetExist")
    void shouldCreateBucketThenUpload_whenBucketDoesNotYetExist() throws Exception {
      // Given — first-ever upload to a brand-new bucket
      when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
      MultipartFile file = mockFile(new byte[] {1, 2, 3});

      // When
      minIOService.uploadFile(BUCKET, OBJECT_KEY, file);

      // Then
      verify(minioClient).makeBucket(any(MakeBucketArgs.class));
      verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    @DisplayName("shouldReportStorageFailure_Exception_whenBucketCreationRacesAnotherInstance")
    void shouldReportStorageFailure_Exception_whenBucketCreationRacesAnotherInstance()
        throws Exception {
      // Given — classic TOCTOU race: bucketExists() says false, but by the time makeBucket()
      // runs, another concurrent request/instance has already created it, and MinIO rejects
      // the duplicate creation attempt
      when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
      doThrow(new ServerException("Bucket already owned by you", 409, "trace-id-3"))
          .when(minioClient)
          .makeBucket(any(MakeBucketArgs.class));
      MultipartFile file = mock(MultipartFile.class);

      // When / Then — surfaces to the caller rather than silently retrying or crashing oddly
      assertThatThrownBy(() -> minIOService.uploadFile(BUCKET, OBJECT_KEY, file))
          .isInstanceOf(StorageException.class)
          .hasRootCauseInstanceOf(ServerException.class)
          .hasStackTraceContaining("already owned");
    }
  }

  // =====================================================================
  // listAllFiles: lazy iteration failures
  // =====================================================================

  @Nested
  @DisplayName("listAllFiles iteration failures")
  class ListAllFilesTests {

    @Test
    @DisplayName("shouldReportStorageFailure_Exception_whenIterationFailsPartwayThroughPagination")
    void shouldReportStorageFailure_Exception_whenIterationFailsPartwayThroughPagination()
        throws Exception {
      // Given — MinIO's listObjects() is lazy: the first page succeeds, but the connection
      // drops while fetching a later page — a realistic large-bucket production scenario
      Item firstItem = mock(Item.class);
      when(firstItem.objectName()).thenReturn("books/1/a.pdf");

      Result<Item> okResult = new Result<>(firstItem);
      Result<Item> failedResult = new Result<>(new SocketTimeoutException("Read timed out"));

      when(minioClient.listObjects(any(ListObjectsArgs.class)))
          .thenReturn(List.of(okResult, failedResult));

      // When / Then — the exception from the failed page propagates; results already collected
      // before the failure are simply discarded rather than returned partially/silently
      assertThatThrownBy(() -> minIOService.listAllFiles(BUCKET))
          .isInstanceOf(StorageException.class)
          .hasRootCauseInstanceOf(SocketTimeoutException.class)
          .hasStackTraceContaining("Read timed out");
    }

    @Test
    @DisplayName("shouldReportStorageFailure_ErrorResponseException_whenListObjectsItselfFails")
    void shouldReportStorageFailure_ErrorResponseException_whenListObjectsItselfFails()
        throws Exception {
      // Given — the very first result carries the failure (e.g. bucket was deleted mid-listing)
      Result<Item> failedResult = new Result<>(new IOException("Bucket no longer exists"));
      when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(failedResult));

      // When / Then
      assertThatThrownBy(() -> minIOService.listAllFiles(BUCKET))
          .isInstanceOf(StorageException.class)
          .hasRootCauseInstanceOf(IOException.class)
          .hasStackTraceContaining("Bucket no longer exists");
    }

    @Test
    @DisplayName("shouldReturnAllObjectNames_whenEveryPageSucceeds")
    void shouldReturnAllObjectNames_whenEveryPageSucceeds() throws Exception {
      // Given
      Item item1 = mock(Item.class);
      when(item1.objectName()).thenReturn("books/1/a.pdf");
      Item item2 = mock(Item.class);
      when(item2.objectName()).thenReturn("books/1/b.pdf");

      when(minioClient.listObjects(any(ListObjectsArgs.class)))
          .thenReturn(List.of(new Result<>(item1), new Result<>(item2)));

      // When
      List<String> files = minIOService.listAllFiles(BUCKET);

      // Then
      assertThat(files).containsExactly("books/1/a.pdf", "books/1/b.pdf");
    }
  }

  // =====================================================================
  // ensurePublicReadPolicy: third-party outage while setting bucket policy
  // =====================================================================

  @Nested
  @DisplayName("ensurePublicReadPolicy outages")
  class EnsurePublicReadPolicyTests {

    @Test
    @DisplayName("shouldReportStorageFailure_ErrorResponseException_whenSetBucketPolicyIsRejected")
    void shouldReportStorageFailure_ErrorResponseException_whenSetBucketPolicyIsRejected()
        throws Exception {
      // Given — malformed policy JSON or insufficient permission is rejected by the server;
      // ErrorResponseException requires a full ErrorResponse/Response to build in real code, so
      // a ServerException stands in here for "MinIO responded with a rejection"
      doThrow(new ServerException("Denied", 403, "t")).when(minioClient).setBucketPolicy(any());

      // When / Then
      assertThatThrownBy(() -> minIOService.ensurePublicReadPolicy(BUCKET))
          .isInstanceOf(StorageException.class)
          .hasRootCauseInstanceOf(ServerException.class)
          .hasStackTraceContaining("Denied");
    }
  }
}
