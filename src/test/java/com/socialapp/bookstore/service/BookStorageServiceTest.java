package com.socialapp.bookstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.socialapp.common.exception.StorageException;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;

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

  /**
   * For {@code uploadBook}. It no longer opens the stream itself — reading and storing both live
   * inside {@code MinIOService} now — so only the filename is stubbed here.
   */
  private MultipartFile mockBookFile(String filename) {
    MultipartFile file = mock(MultipartFile.class);
    when(file.getOriginalFilename()).thenReturn(filename);
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
    @DisplayName("should wrap any failure as a StorageException")
    void shouldThrowStorageException_whenUploadFails() throws Exception {
      // Given
      MultipartFile file = mockBookFile("novel.pdf");
      when(minIOService.uploadFile(anyString(), anyString(), any()))
          .thenThrow(
              new StorageException("Could not store the object", new IOException("minio down")));

      // When / Then
      assertThatThrownBy(() -> bookStorageService.uploadBook(AUTHOR_ID, file))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Could not store")
          .hasRootCauseInstanceOf(IOException.class);
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
    @DisplayName("should wrap any failure as a StorageException")
    void shouldThrowStorageException_whenUploadFails() throws Exception {
      // Given
      byte[] bytes = {1, 2, 3};
      when(minIOService.uploadBytes(anyString(), anyString(), any(), anyString()))
          .thenThrow(
              new StorageException("Could not store the object", new IOException("minio down")));

      // When / Then
      assertThatThrownBy(() -> bookStorageService.uploadPreview(AUTHOR_ID, bytes, "pdf"))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Could not store")
          .hasRootCauseInstanceOf(IOException.class);
    }
  }

  // =====================================================================
  // uploadCover
  // =====================================================================

  @Nested
  @DisplayName("uploadCover")
  class UploadCoverTests {

    @Test
    @DisplayName("should upload the cover and return its object key, not a URL")
    void shouldUploadAndReturnObjectKey_whenSuccessful() throws Exception {
      // Given
      MultipartFile file = mockCoverFile("cover.jpg");

      // When
      String key = bookStorageService.uploadCover(AUTHOR_ID, file);

      // Then — B4: returning a presigned URL here is what got persisted and died after 24h
      assertThat(key).startsWith("covers/1/").endsWith(".jpg");
      assertThat(key).doesNotContain("X-Amz-Signature").doesNotContain("http");
      verify(minIOService).uploadFile(eq("book-covers"), eq(key), eq(file));
      verify(minioClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

    @Test
    @DisplayName("should wrap an upload failure as a StorageException")
    void shouldThrowStorageException_whenUploadFails() throws Exception {
      // Given
      MultipartFile file = mockCoverFile("cover.jpg");
      when(minIOService.uploadFile(anyString(), anyString(), any()))
          .thenThrow(
              new StorageException("Could not store the object", new IOException("minio down")));

      // When / Then
      assertThatThrownBy(() -> bookStorageService.uploadCover(AUTHOR_ID, file))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Could not store")
          .hasRootCauseInstanceOf(IOException.class);
    }
  }

  // =====================================================================
  // getCoverUrl
  // =====================================================================

  @Nested
  @DisplayName("getCoverUrl")
  class GetCoverUrlTests {

    @Test
    @DisplayName("should sign the key against the covers bucket")
    void shouldSignCoverKey() throws Exception {
      // Given
      when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
          .thenReturn("https://cdn/cover-url");

      // When
      String url = bookStorageService.getCoverUrl("covers/1/abc.jpg");

      // Then
      assertThat(url).isEqualTo("https://cdn/cover-url");
    }

    @Test
    @DisplayName("should return null for a missing key rather than signing nothing")
    void shouldReturnNull_whenKeyIsAbsent() throws Exception {
      // When / Then — most books have no cover
      assertThat(bookStorageService.getCoverUrl(null)).isNull();
      assertThat(bookStorageService.getCoverUrl("  ")).isNull();
      verify(minioClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

    @Test
    @DisplayName("should return null instead of throwing when signing fails")
    void shouldReturnNull_whenSigningFails() throws Exception {
      // Given — a missing bucket is an ordinary state on a freshly reset MinIO
      when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
          .thenThrow(new IOException("no such bucket"));

      // When / Then — this runs on the feed read path, so throwing here would take the whole
      // feed down over a missing picture
      assertThat(bookStorageService.getCoverUrl("covers/1/abc.jpg")).isNull();
    }
  }

  // =====================================================================
  // deleteQuietly
  // =====================================================================

  @Nested
  @DisplayName("deleteQuietly")
  class DeleteQuietlyTests {

    @Test
    @DisplayName("should remove the object from the given bucket")
    void shouldRemoveObject() throws Exception {
      // When
      bookStorageService.deleteQuietly("books", "books/1/abc.pdf");

      // Then
      verify(minioClient).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    @DisplayName("should do nothing when there is no key to remove")
    void shouldDoNothing_whenKeyIsAbsent() throws Exception {
      // When
      bookStorageService.deleteQuietly("books", null);
      bookStorageService.deleteQuietly("books", "  ");

      // Then — nothing was uploaded at that step, so there is nothing to undo
      verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    @DisplayName("should swallow a removal failure rather than mask the original error")
    void shouldSwallowRemovalFailure() throws Exception {
      // Given — the caller is already unwinding an exception when this runs
      org.mockito.Mockito.doThrow(new IOException("minio down"))
          .when(minioClient)
          .removeObject(any(RemoveObjectArgs.class));

      // When / Then
      org.assertj.core.api.Assertions.assertThatCode(
              () -> bookStorageService.deleteQuietly("books", "books/1/abc.pdf"))
          .doesNotThrowAnyException();
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
    @DisplayName("should wrap a presigned URL generation failure as a StorageException")
    void shouldThrowStorageException_whenPresignedUrlGenerationFails() throws Exception {
      // Given
      when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
          .thenThrow(new IOException("network fail"));

      // When / Then
      assertThatThrownBy(() -> bookStorageService.getDownloadUrl("file-key"))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Failed to generate download URL")
          .hasCauseInstanceOf(IOException.class);
    }
  }
}
