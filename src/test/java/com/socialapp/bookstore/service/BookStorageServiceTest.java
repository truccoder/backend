package com.socialapp.bookstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.cloud.minio.MinIOService;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;

/**
 * Component (unit) tests for {@link BookStorageService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3 BDD
 * Given/When/Then) — see {@code PostServiceTest} for the full rationale. {@link MinioClient} and
 * {@link MinIOService} are mocked so no MinIO server is ever contacted.
 */
@ExtendWith(MockitoExtension.class)
class BookStorageServiceTest {

  private static final Integer AUTHOR_ID = 1;

  @Mock private MinioClient minioClient;
  @Mock private MinIOService minIOService;

  @InjectMocks private BookStorageService bookStorageService;

  /** For {@code uploadBook}, which reads the stream via try-with-resources. */
  private MultipartFile mockBookFile(String filename) throws IOException {
    MultipartFile file = mock(MultipartFile.class);
    when(file.getOriginalFilename()).thenReturn(filename);
    when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
    return file;
  }

  /** For {@code uploadCover}, which never reads the file's stream directly. */
  private MultipartFile mockCoverFile(String filename) {
    MultipartFile file = mock(MultipartFile.class);
    when(file.getOriginalFilename()).thenReturn(filename);
    return file;
  }

  // =====================================================================
  // uploadBook
  // =====================================================================

  @Nested
  @DisplayName("uploadBook")
  class UploadBookTests {

    @Test
    @DisplayName("should upload and return an object key using the file's extension")
    void shouldUploadAndReturnObjectKey_whenExtensionPresent() throws Exception {
      // Given
      MultipartFile file = mockBookFile("novel.pdf");

      // When
      String key = bookStorageService.uploadBook(AUTHOR_ID, file);

      // Then
      assertThat(key).startsWith("books/1/").endsWith(".pdf");
      verify(minIOService).uploadFile(eq("books"), eq(key), eq(file));
    }

    @Test
    @DisplayName("should default to a pdf extension when the filename has none")
    void shouldDefaultToPdfExtension_whenFilenameHasNoExtension() throws Exception {
      // Given
      MultipartFile file = mockBookFile("novel");

      // When
      String key = bookStorageService.uploadBook(AUTHOR_ID, file);

      // Then
      assertThat(key).endsWith(".pdf");
    }

    @Test
    @DisplayName("should default to a pdf extension when the filename is null")
    void shouldDefaultToPdfExtension_whenFilenameIsNull() throws Exception {
      // Given
      MultipartFile file = mockBookFile(null);

      // When
      String key = bookStorageService.uploadBook(AUTHOR_ID, file);

      // Then
      assertThat(key).endsWith(".pdf");
    }

    @Test
    @DisplayName("should wrap any failure as a RuntimeException")
    void shouldThrowRuntimeException_whenUploadFails() throws Exception {
      // Given
      MultipartFile file = mockBookFile("novel.pdf");
      when(minIOService.uploadFile(anyString(), anyString(), any()))
          .thenThrow(new IOException("minio down"));

      // When / Then
      assertThatThrownBy(() -> bookStorageService.uploadBook(AUTHOR_ID, file))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Failed to upload book file")
          .hasCauseInstanceOf(IOException.class);
    }
  }

  // =====================================================================
  // uploadPreview
  // =====================================================================

  @Nested
  @DisplayName("uploadPreview")
  class UploadPreviewTests {

    @Test
    @DisplayName("should use the epub content type for an epub preview")
    void shouldUseEpubContentType_whenExtensionIsEpub() throws Exception {
      // Given
      byte[] bytes = {1, 2, 3};

      // When
      String key = bookStorageService.uploadPreview(AUTHOR_ID, bytes, "epub");

      // Then
      assertThat(key).startsWith("previews/1/").endsWith(".epub");
      verify(minIOService).uploadBytes("books", key, bytes, "application/epub+zip");
    }

    @Test
    @DisplayName("should use the pdf content type for any non-epub preview")
    void shouldUsePdfContentType_whenExtensionIsNotEpub() throws Exception {
      // Given
      byte[] bytes = {1, 2, 3};

      // When
      String key = bookStorageService.uploadPreview(AUTHOR_ID, bytes, "pdf");

      // Then
      verify(minIOService).uploadBytes("books", key, bytes, "application/pdf");
    }

    @Test
    @DisplayName("should wrap any failure as a RuntimeException")
    void shouldThrowRuntimeException_whenUploadFails() throws Exception {
      // Given
      byte[] bytes = {1, 2, 3};
      when(minIOService.uploadBytes(anyString(), anyString(), any(), anyString()))
          .thenThrow(new IOException("minio down"));

      // When / Then
      assertThatThrownBy(() -> bookStorageService.uploadPreview(AUTHOR_ID, bytes, "pdf"))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Failed to upload book preview file")
          .hasCauseInstanceOf(IOException.class);
    }
  }

  // =====================================================================
  // uploadCover
  // =====================================================================

  @Nested
  @DisplayName("uploadCover")
  class UploadCoverTests {

    @Test
    @DisplayName("should upload the cover and return its presigned URL")
    void shouldUploadAndReturnPresignedUrl_whenSuccessful() throws Exception {
      // Given
      MultipartFile file = mockCoverFile("cover.jpg");
      when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
          .thenReturn("https://cdn/cover-url");

      // When
      String url = bookStorageService.uploadCover(AUTHOR_ID, file);

      // Then
      assertThat(url).isEqualTo("https://cdn/cover-url");
      verify(minIOService).uploadFile(eq("book-covers"), anyString(), eq(file));
    }

    @Test
    @DisplayName("should wrap an upload failure as a RuntimeException")
    void shouldThrowRuntimeException_whenUploadFails() throws Exception {
      // Given
      MultipartFile file = mockCoverFile("cover.jpg");
      when(minIOService.uploadFile(anyString(), anyString(), any()))
          .thenThrow(new IOException("minio down"));

      // When / Then
      assertThatThrownBy(() -> bookStorageService.uploadCover(AUTHOR_ID, file))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Failed to upload cover image")
          .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("should wrap a presigned URL generation failure as a RuntimeException")
    void shouldThrowRuntimeException_whenPresignedUrlGenerationFails() throws Exception {
      // Given
      MultipartFile file = mockCoverFile("cover.jpg");
      when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
          .thenThrow(new IOException("network fail"));

      // When / Then
      assertThatThrownBy(() -> bookStorageService.uploadCover(AUTHOR_ID, file))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Failed to upload cover image")
          .cause()
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Failed to generate download URL");
    }
  }

  // =====================================================================
  // getDownloadUrl / getPreviewUrl
  // =====================================================================

  @Nested
  @DisplayName("getDownloadUrl / getPreviewUrl")
  class GetUrlTests {

    @Test
    @DisplayName("should return a presigned download URL from the books bucket")
    void shouldReturnPresignedUrl_forDownload() throws Exception {
      // Given
      when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
          .thenReturn("https://cdn/file-key");

      // When
      String url = bookStorageService.getDownloadUrl("file-key");

      // Then
      assertThat(url).isEqualTo("https://cdn/file-key");
    }

    @Test
    @DisplayName("should return a presigned preview URL from the books bucket")
    void shouldReturnPresignedUrl_forPreview() throws Exception {
      // Given
      when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
          .thenReturn("https://cdn/preview-key");

      // When
      String url = bookStorageService.getPreviewUrl("preview-key");

      // Then
      assertThat(url).isEqualTo("https://cdn/preview-key");
    }

    @Test
    @DisplayName("should wrap a presigned URL generation failure as a RuntimeException")
    void shouldThrowRuntimeException_whenPresignedUrlGenerationFails() throws Exception {
      // Given
      when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
          .thenThrow(new IOException("network fail"));

      // When / Then
      assertThatThrownBy(() -> bookStorageService.getDownloadUrl("file-key"))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Failed to generate download URL")
          .hasCauseInstanceOf(IOException.class);
    }
  }
}
