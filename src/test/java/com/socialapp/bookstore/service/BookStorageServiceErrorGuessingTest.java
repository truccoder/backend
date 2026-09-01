package com.socialapp.bookstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.cloud.minio.MinIOService;
import com.socialapp.common.exception.StorageException;
import com.socialapp.common.exception.ValidationException;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.errors.MinioException;
import io.minio.errors.ServerException;

/**
 * Stage 5 (Experience-based Testing) — Error Guessing technique per ISTQB CTFL v4.0.1 Section
 * 4.5.3, applied to {@link BookStorageService}. Unlike the Stage 2 unit tests in {@link
 * BookStorageServiceTest} (which check ordinary branches), these tests specifically hunt for
 * worst-case production scenarios around the third-party MinIO dependency: corrupted/empty
 * files, network outages, timeouts, and downstream 5xx responses.
 *
 * <p>{@link MinioClient} and {@link MinIOService} are mocked — no real MinIO server is ever
 * contacted, and no network call leaves the JVM. Every scenario asserts the service degrades
 * gracefully into a typed {@link StorageException} (or {@link ValidationException} for bad
 * input), never a raw {@link NullPointerException} or an unwrapped third-party exception type.
 */
@ExtendWith(MockitoExtension.class)
class BookStorageServiceErrorGuessingTest {

  private static final Integer AUTHOR_ID = 1;

  @Mock private MinioClient minioClient;
  @Mock private MinIOService minIOService;

  @InjectMocks private BookStorageService bookStorageService;

  /**
   * For {@code uploadBook}. The stream is no longer read here — opening it, storing it and
   * translating MinIO's failures all live inside {@code MinIOService} now — so only the filename
   * the object key is built from needs stubbing.
   */
  private static MultipartFile mockBookFile(String filename, byte[] bytes) {
    MultipartFile file = mock(MultipartFile.class);
    when(file.getOriginalFilename()).thenReturn(filename);
    return file;
  }

  /** For {@code uploadCover}, which never reads the file's stream directly. */
  private static MultipartFile mockCoverFile(String filename) {
    MultipartFile file = mock(MultipartFile.class);
    when(file.getOriginalFilename()).thenReturn(filename);
    return file;
  }

  // =====================================================================
  // Corrupted / empty file inputs
  // =====================================================================

  @Nested
  @DisplayName("Corrupted or empty file inputs")
  class CorruptedOrEmptyFileTests {

    @Test
    @DisplayName("shouldUploadSuccessfully_whenBookFileIsZeroBytes")
    void shouldUploadSuccessfully_whenBookFileIsZeroBytes() throws Exception {
      // Given — a 0-byte file is a valid (if unusual) upload; MinIO itself accepts empty objects
      MultipartFile emptyFile = mockBookFile("empty.pdf", new byte[0]);

      // When
      String key = bookStorageService.uploadBook(AUTHOR_ID, emptyFile);

      // Then — no crash, object key still generated normally
      assertThat(key).startsWith("books/1/").endsWith(".pdf");
    }

    @Test
    @DisplayName("shouldThrowStorageException_whenBookFileStreamIsCorruptedMidRead")
    void shouldThrowStorageException_whenBookFileStreamIsCorruptedMidRead() throws Exception {
      // Given — simulates a truncated/corrupted upload: the client's temp file storage fails
      // while the server tries to read it (distinct from a MinIO-side failure)
      // Reading the stream moved into MinIOService, so that is where a truncated body now
      // surfaces — and it arrives here already translated to StorageException, exactly like a
      // MinIO-side failure. MinIOServiceErrorGuessingTest covers the read itself.
      MultipartFile corruptedFile = mock(MultipartFile.class);
      when(corruptedFile.getOriginalFilename()).thenReturn("corrupted.pdf");
      when(minIOService.uploadFile(anyString(), anyString(), any()))
          .thenThrow(
              new StorageException(
                  "Could not store the object",
                  new IOException("Broken pipe: truncated multipart body")));

      // When / Then
      assertThatThrownBy(() -> bookStorageService.uploadBook(AUTHOR_ID, corruptedFile))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Could not store")
          .hasRootCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("shouldThrowValidationException_notNullPointerException_whenBookFileIsNull")
    void shouldThrowValidationException_notNullPointerException_whenBookFileIsNull() {
      // When / Then — must fail predictably, never crash the caller with a raw NPE
      assertThatThrownBy(() -> bookStorageService.uploadBook(AUTHOR_ID, null))
          .isInstanceOf(ValidationException.class)
          .isNotInstanceOf(NullPointerException.class)
          .hasMessageContaining("File must not be null");
    }

    @Test
    @DisplayName("shouldThrowValidationException_notNullPointerException_whenCoverFileIsNull")
    void shouldThrowValidationException_notNullPointerException_whenCoverFileIsNull() {
      // When / Then
      assertThatThrownBy(() -> bookStorageService.uploadCover(AUTHOR_ID, null))
          .isInstanceOf(ValidationException.class)
          .isNotInstanceOf(NullPointerException.class)
          .hasMessageContaining("File must not be null");
    }
  }

  // =====================================================================
  // Third-party (MinIO) outages: timeout, network error, server 5xx
  // =====================================================================

  @Nested
  @DisplayName("MinIO client outages during upload")
  class ThirdPartyOutageTests {

    @Test
    @DisplayName("shouldThrowStorageException_whenMinioClientTimesOut")
    void shouldThrowStorageException_whenMinioClientTimesOut() throws Exception {
      // Given — the MinIO server accepted the connection but never responded in time
      MultipartFile file = mockBookFile("novel.pdf", new byte[] {1, 2, 3});
      when(minIOService.uploadFile(anyString(), anyString(), any()))
          .thenThrow(
              new StorageException(
                  "Could not store the object", new SocketTimeoutException("Read timed out")));

      // When / Then
      assertThatThrownBy(() -> bookStorageService.uploadBook(AUTHOR_ID, file))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Could not store")
          .hasRootCauseInstanceOf(SocketTimeoutException.class);
    }

    @Test
    @DisplayName("shouldThrowStorageException_whenNetworkConnectionIsRefused")
    void shouldThrowStorageException_whenNetworkConnectionIsRefused() throws Exception {
      // Given — MinIO host is unreachable (container down, DNS failure, firewall, etc.)
      MultipartFile file = mockBookFile("novel.pdf", new byte[] {1, 2, 3});
      when(minIOService.uploadFile(anyString(), anyString(), any()))
          .thenThrow(
              new StorageException(
                  "Could not store the object", new ConnectException("Connection refused")));

      // When / Then
      assertThatThrownBy(() -> bookStorageService.uploadBook(AUTHOR_ID, file))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Could not store")
          .hasRootCauseInstanceOf(ConnectException.class);
    }

    @Test
    @DisplayName("shouldThrowStorageException_whenMinioServerReturns500")
    void shouldThrowStorageException_whenMinioServerReturns500() throws Exception {
      // Given — MinIO responded, but with a genuine server-side failure
      MultipartFile file = mockBookFile("novel.pdf", new byte[] {1, 2, 3});
      when(minIOService.uploadFile(anyString(), anyString(), any()))
          .thenThrow(
              new StorageException(
                  "Could not store the object",
                  new ServerException(
                      "We encountered an internal error, please try again.",
                      500,
                      "trace-id-abc123")));

      // When / Then
      assertThatThrownBy(() -> bookStorageService.uploadBook(AUTHOR_ID, file))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Could not store")
          .hasRootCauseInstanceOf(ServerException.class);
    }

    @Test
    @DisplayName("shouldThrowStorageException_whenMinioThrowsGenericMinioException")
    void shouldThrowStorageException_whenMinioThrowsGenericMinioException() throws Exception {
      // Given — any other checked failure from the MinIO SDK's exception hierarchy
      byte[] previewBytes = {1, 2, 3};
      when(minIOService.uploadBytes(anyString(), anyString(), any(), anyString()))
          .thenThrow(
              new StorageException(
                  "Could not store the object",
                  new MinioException("Unexpected internal SDK failure")));

      // When / Then
      assertThatThrownBy(() -> bookStorageService.uploadPreview(AUTHOR_ID, previewBytes, "pdf"))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Could not store")
          .hasRootCauseInstanceOf(MinioException.class);
    }

    @Test
    @DisplayName("shouldPreserveOriginalCauseChain_whenMinioClientFails")
    void shouldPreserveOriginalCauseChain_whenMinioClientFails() throws Exception {
      // Given — diagnosability matters: the original low-level exception must not be swallowed
      MultipartFile file = mockBookFile("novel.pdf", new byte[] {1, 2, 3});
      SocketTimeoutException original = new SocketTimeoutException("Read timed out");
      when(minIOService.uploadFile(anyString(), anyString(), any()))
          .thenThrow(new StorageException("Could not store the object", original));

      // When / Then — MinIOService is the one that translates now, and BookStorageService passes
      // its exception through untouched, so the SDK's own exception is still reachable as the root
      // cause. That is the property worth pinning: a timeout must stay diagnosable in the log.
      assertThatThrownBy(() -> bookStorageService.uploadBook(AUTHOR_ID, file))
          .isInstanceOf(StorageException.class)
          .rootCause()
          .isSameAs(original);
    }

    @Test
    @DisplayName("shouldNotThrowNullPointerException_whenMinioExceptionHasNullMessage")
    void shouldNotThrowNullPointerException_whenMinioExceptionHasNullMessage() throws Exception {
      // Given — a third-party exception with no message at all is a realistic worst case
      // (e.g. certain low-level socket/SSL failures carry a null getMessage())
      MultipartFile file = mockBookFile("novel.pdf", new byte[] {1, 2, 3});
      when(minIOService.uploadFile(anyString(), anyString(), any()))
          .thenThrow(
              new StorageException("Could not store the object", new IOException((String) null)));

      // When / Then — the service's own wrapping message must still be intact, no NPE while
      // building the StorageException itself
      assertThatThrownBy(() -> bookStorageService.uploadBook(AUTHOR_ID, file))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Could not store");
    }
  }

  // =====================================================================
  // Third-party (MinIO) outages: presigned URL generation
  // =====================================================================

  @Nested
  @DisplayName("MinIO client outages during presigned URL generation")
  class PresignedUrlOutageTests {

    @Test
    @DisplayName("shouldThrowStorageException_whenPresignedUrlGenerationTimesOut")
    void shouldThrowStorageException_whenPresignedUrlGenerationTimesOut() throws Exception {
      // Given
      when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
          .thenThrow(new SocketTimeoutException("Read timed out"));

      // When / Then
      assertThatThrownBy(() -> bookStorageService.getDownloadUrl("file-key"))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Failed to generate download URL")
          .hasCauseInstanceOf(SocketTimeoutException.class);
    }

    @Test
    @DisplayName("shouldThrowStorageException_whenPresignedUrlGenerationHitsServerError")
    void shouldThrowStorageException_whenPresignedUrlGenerationHitsServerError() throws Exception {
      // Given
      when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
          .thenThrow(new ServerException("Internal error", 500, "trace-id-xyz789"));

      // When / Then
      assertThatThrownBy(() -> bookStorageService.getPreviewUrl("preview-key"))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Failed to generate download URL")
          .hasCauseInstanceOf(ServerException.class);
    }

    @Test
    @DisplayName("shouldWrapWithoutLeakingRawMinioType_whenUploadSucceedsButUrlGenerationFails")
    void shouldWrapWithoutLeakingRawMinioType_whenUploadSucceedsButUrlGenerationFails()
        throws Exception {
      // Given — uploadCover no longer signs anything (B4), so the equivalent split is the
      // download path: the object is there, the presigned-URL round-trip is what fails
      when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
          .thenThrow(new ConnectException("Connection refused"));

      // When / Then — the caller still only ever sees our own exception type
      assertThatThrownBy(() -> bookStorageService.getDownloadUrl("books/1/abc.pdf"))
          .isInstanceOf(StorageException.class)
          .hasCauseInstanceOf(ConnectException.class);
    }
  }
}
