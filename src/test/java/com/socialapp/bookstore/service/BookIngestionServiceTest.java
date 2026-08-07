package com.socialapp.bookstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;

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

import com.socialapp.bookstore.dto.CreateBookRequestDto;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.entity.enums.FileFormat;
import com.socialapp.bookstore.repository.BookRepository;
import com.socialapp.common.exception.ValidationException;

/**
 * Component (unit) tests for {@link BookIngestionService}, per ISTQB CTFL v4.0.1:
 *
 * <ul>
 *   <li><b>Component testing</b> (Section 2.2.1) — {@code BookRepository}, {@code
 *       BookStorageService} and {@code BookPreviewGenerator} are mocked with Mockito ({@code
 *       @ExtendWith(MockitoExtension.class)}, no Spring context).
 *   <li><b>Test Pyramid</b> (Section 5.1.6) — fast, isolated, bottom-layer tests.
 *   <li><b>Branch testing / branch coverage</b> (Section 4.3.2, white-box) — inputs are chosen
 *       with knowledge of the free/paid, preview/no-preview and cover/no-cover branches so every
 *       control-flow-graph branch is driven to both true and false.
 *   <li><b>BDD Given/When/Then</b> (Section 2.1.3).
 * </ul>
 *
 * <p>These moved here unchanged when the write path was split out of {@code BookService}; they
 * exercise the same behaviour through its new home.
 */
@ExtendWith(MockitoExtension.class)
class BookIngestionServiceTest {

  private static final Integer AUTHOR_ID = 1;
  private static final Integer POST_ID = 100;

  @Mock private BookRepository bookRepository;
  @Mock private BookStorageService bookStorageService;
  @Mock private BookPreviewGenerator bookPreviewGenerator;

  @InjectMocks private BookIngestionService ingestionService;

  @Captor private ArgumentCaptor<BookEntity> bookCaptor;

  // ---------------------------------------------------------------------
  // Test data builders
  // ---------------------------------------------------------------------

  private static CreateBookRequestDto bookRequest(Long price, Integer previewPages) {
    CreateBookRequestDto dto = new CreateBookRequestDto();
    dto.setTitle("My Book");
    dto.setDescription("A great book");
    dto.setPrice(price);
    dto.setPreviewPages(previewPages);
    return dto;
  }

  private static MultipartFile mockFile(String filename) {
    MultipartFile file = mock(MultipartFile.class);
    when(file.isEmpty()).thenReturn(false);
    when(file.getOriginalFilename()).thenReturn(filename);
    return file;
  }

  // =====================================================================
  // ingest
  // =====================================================================

  @Nested
  @DisplayName("ingest")
  class IngestTests {

    @Test
    @DisplayName("should reject a null book file before touching any dependency")
    void shouldThrowValidationException_whenBookFileIsNull() {
      // Given / When / Then
      assertThatThrownBy(
              () ->
                  ingestionService.ingest(AUTHOR_ID, POST_ID, bookRequest(null, null), null, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Book file is required");
      verifyNoInteractions(bookRepository, bookStorageService, bookPreviewGenerator);
    }

    @Test
    @DisplayName("should reject an empty book file")
    void shouldThrowValidationException_whenBookFileIsEmpty() {
      // Given
      MultipartFile bookFile = mock(MultipartFile.class);
      when(bookFile.isEmpty()).thenReturn(true);

      // When / Then
      assertThatThrownBy(
              () ->
                  ingestionService.ingest(
                      AUTHOR_ID, POST_ID, bookRequest(null, null), bookFile, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Book file is required");
      verifyNoInteractions(bookRepository, bookStorageService, bookPreviewGenerator);
    }

    @Test
    @DisplayName("should reject a filename with an unsupported extension")
    void shouldThrowValidationException_whenFileFormatNotAllowed() {
      // Given
      MultipartFile bookFile = mockFile("book.txt");

      // When / Then
      assertThatThrownBy(
              () ->
                  ingestionService.ingest(
                      AUTHOR_ID, POST_ID, bookRequest(null, null), bookFile, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Only PDF and EPUB formats are supported");
      verifyNoInteractions(bookRepository, bookStorageService, bookPreviewGenerator);
    }

    @Test
    @DisplayName("should reject a filename with no extension at all")
    void shouldThrowValidationException_whenFilenameHasNoExtension() {
      // Given
      MultipartFile bookFile = mockFile("book");

      // When / Then
      assertThatThrownBy(
              () ->
                  ingestionService.ingest(
                      AUTHOR_ID, POST_ID, bookRequest(null, null), bookFile, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Only PDF and EPUB formats are supported");
    }

    @Test
    @DisplayName("should reject a null filename")
    void shouldThrowValidationException_whenFilenameIsNull() {
      // Given
      MultipartFile bookFile = mockFile(null);

      // When / Then
      assertThatThrownBy(
              () ->
                  ingestionService.ingest(
                      AUTHOR_ID, POST_ID, bookRequest(null, null), bookFile, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Only PDF and EPUB formats are supported");
    }

    @Test
    @DisplayName("should save a free PDF book, counting its pages, with no cover file")
    void shouldSucceed_withFreePdfBookAndNoCoverFile() throws IOException {
      // Given
      MultipartFile bookFile = mockFile("book.pdf");
      byte[] bytes = {1, 2, 3};
      when(bookStorageService.uploadBook(AUTHOR_ID, bookFile)).thenReturn("book-key");
      when(bookFile.getBytes()).thenReturn(bytes);
      when(bookPreviewGenerator.countPdfPages(bytes)).thenReturn(42);

      // When
      BookEntity result =
          ingestionService.ingest(AUTHOR_ID, POST_ID, bookRequest(null, null), bookFile, null);

      // Then
      verify(bookRepository).save(bookCaptor.capture());
      BookEntity saved = bookCaptor.getValue();
      assertThat(saved).isSameAs(result);
      assertThat(saved.getIsFree()).isTrue();
      assertThat(saved.getFileFormat()).isEqualTo(FileFormat.PDF);
      assertThat(saved.getTotalPages()).isEqualTo(42);
      assertThat(saved.getPreviewPages()).isZero();
      assertThat(saved.getPrice()).isZero();
      assertThat(saved.getPreviewFileKey()).isNull();
      assertThat(saved.getCoverImageKey()).isNull();
      assertThat(saved.getFileKey()).isEqualTo("book-key");
      verify(bookStorageService, never()).uploadCover(any(), any());
    }

    @Test
    @DisplayName("should skip cover upload when the cover file is present but empty")
    void shouldSkipCoverUpload_whenCoverFileIsEmpty() throws IOException {
      // Given
      MultipartFile bookFile = mockFile("book.pdf");
      MultipartFile coverFile = mock(MultipartFile.class);
      when(coverFile.isEmpty()).thenReturn(true);
      byte[] bytes = {1, 2, 3};
      when(bookStorageService.uploadBook(AUTHOR_ID, bookFile)).thenReturn("book-key");
      when(bookFile.getBytes()).thenReturn(bytes);
      when(bookPreviewGenerator.countPdfPages(bytes)).thenReturn(10);

      // When
      ingestionService.ingest(AUTHOR_ID, POST_ID, bookRequest(null, null), bookFile, coverFile);

      // Then
      verify(bookStorageService, never()).uploadCover(any(), any());
    }

    @Test
    @DisplayName("should upload the cover when a non-empty cover file is provided")
    void shouldUploadCover_whenCoverFileProvidedAndNotEmpty() throws IOException {
      // Given
      MultipartFile bookFile = mockFile("book.pdf");
      MultipartFile coverFile = mock(MultipartFile.class);
      when(coverFile.isEmpty()).thenReturn(false);
      byte[] bytes = {1, 2, 3};
      when(bookStorageService.uploadBook(AUTHOR_ID, bookFile)).thenReturn("book-key");
      when(bookStorageService.uploadCover(AUTHOR_ID, coverFile)).thenReturn("cover-key");
      when(bookFile.getBytes()).thenReturn(bytes);
      when(bookPreviewGenerator.countPdfPages(bytes)).thenReturn(10);

      // When
      ingestionService.ingest(AUTHOR_ID, POST_ID, bookRequest(null, null), bookFile, coverFile);

      // Then
      verify(bookRepository).save(bookCaptor.capture());
      assertThat(bookCaptor.getValue().getCoverImageKey()).isEqualTo("cover-key");
    }

    @Test
    @DisplayName("should treat a zero price as free and count EPUB chapters")
    void shouldCountEpubChapters_whenPriceIsZero() throws IOException {
      // Given
      MultipartFile bookFile = mockFile("book.epub");
      byte[] bytes = {9, 9};
      when(bookStorageService.uploadBook(AUTHOR_ID, bookFile)).thenReturn("book-key");
      when(bookFile.getBytes()).thenReturn(bytes);
      when(bookPreviewGenerator.countEpubChapters(bytes)).thenReturn(7);

      // When
      ingestionService.ingest(AUTHOR_ID, POST_ID, bookRequest(0L, null), bookFile, null);

      // Then
      verify(bookRepository).save(bookCaptor.capture());
      BookEntity saved = bookCaptor.getValue();
      assertThat(saved.getIsFree()).isTrue();
      assertThat(saved.getFileFormat()).isEqualTo(FileFormat.EPUB);
      assertThat(saved.getTotalPages()).isEqualTo(7);
    }

    @Test
    @DisplayName("should wrap an IOException as a ValidationException when counting pages fails")
    void shouldThrowValidationException_whenCountingPagesFails() throws IOException {
      // Given
      MultipartFile bookFile = mockFile("book.pdf");
      when(bookFile.getBytes()).thenThrow(new IOException("disk read failure"));

      // When / Then
      assertThatThrownBy(
              () ->
                  ingestionService.ingest(
                      AUTHOR_ID, POST_ID, bookRequest(null, null), bookFile, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("corrupted or not a valid")
          .hasCauseInstanceOf(IOException.class);
      verify(bookRepository, never()).save(any());
      // B11: nothing may reach MinIO on a rejected book — Postgres rolls back, the bucket does not
      verify(bookStorageService, never()).uploadBook(any(), any());
      verify(bookStorageService, never()).uploadCover(any(), any());
      verify(bookStorageService, never()).uploadPreview(any(), any(), any());
    }

    @Test
    @DisplayName("should reject a paid book with no preview pages configured")
    void shouldThrowValidationException_whenPaidBookMissingPreviewPages() {
      // Given
      MultipartFile bookFile = mockFile("book.pdf");

      // When / Then
      assertThatThrownBy(
              () ->
                  ingestionService.ingest(
                      AUTHOR_ID, POST_ID, bookRequest(1000L, null), bookFile, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Paid books must have preview pages configured");
      verify(bookRepository, never()).save(any());
      // B11: nothing may reach MinIO on a rejected book — Postgres rolls back, the bucket does not
      verify(bookStorageService, never()).uploadBook(any(), any());
      verify(bookStorageService, never()).uploadCover(any(), any());
      verify(bookStorageService, never()).uploadPreview(any(), any(), any());
    }

    @Test
    @DisplayName("should reject a paid book with a non-positive preview page count")
    void shouldThrowValidationException_whenPaidBookHasNonPositivePreviewPages() {
      // Given
      MultipartFile bookFile = mockFile("book.pdf");

      // When / Then
      assertThatThrownBy(
              () ->
                  ingestionService.ingest(
                      AUTHOR_ID, POST_ID, bookRequest(1000L, 0), bookFile, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Paid books must have preview pages configured");
    }

    @Test
    @DisplayName("should generate a PDF preview for a paid PDF book")
    void shouldGeneratePdfPreview_whenPaidBookIsPdf() throws IOException {
      // Given
      MultipartFile bookFile = mockFile("book.pdf");
      byte[] original = {1, 2, 3};
      byte[] previewBytes = {4, 5};
      when(bookFile.getBytes()).thenReturn(original);
      when(bookPreviewGenerator.generatePdfPreview(original, 5))
          .thenReturn(new BookPreviewResult(previewBytes, 20));
      when(bookStorageService.uploadPreview(AUTHOR_ID, previewBytes, "pdf"))
          .thenReturn("preview-key");

      // When
      ingestionService.ingest(AUTHOR_ID, POST_ID, bookRequest(1000L, 5), bookFile, null);

      // Then
      verify(bookRepository).save(bookCaptor.capture());
      BookEntity saved = bookCaptor.getValue();
      assertThat(saved.getIsFree()).isFalse();
      assertThat(saved.getPreviewFileKey()).isEqualTo("preview-key");
      assertThat(saved.getTotalPages()).isEqualTo(20);
      assertThat(saved.getPreviewPages()).isEqualTo(5);
      assertThat(saved.getPrice()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("should generate an EPUB preview for a paid EPUB book")
    void shouldGenerateEpubPreview_whenPaidBookIsEpub() throws IOException {
      // Given
      MultipartFile bookFile = mockFile("book.epub");
      byte[] original = {1, 2, 3};
      byte[] previewBytes = {4, 5};
      when(bookFile.getBytes()).thenReturn(original);
      when(bookPreviewGenerator.generateEpubPreview(original, 3))
          .thenReturn(new BookPreviewResult(previewBytes, 10));
      when(bookStorageService.uploadPreview(AUTHOR_ID, previewBytes, "epub"))
          .thenReturn("preview-key-epub");

      // When
      ingestionService.ingest(AUTHOR_ID, POST_ID, bookRequest(1000L, 3), bookFile, null);

      // Then
      verify(bookRepository).save(bookCaptor.capture());
      assertThat(bookCaptor.getValue().getFileFormat()).isEqualTo(FileFormat.EPUB);
      assertThat(bookCaptor.getValue().getPreviewFileKey()).isEqualTo("preview-key-epub");
    }

    @Test
    @DisplayName(
        "should reject when the requested preview size is not smaller than the book's total")
    void shouldThrowValidationException_whenPreviewPagesGreaterOrEqualTotalUnits()
        throws IOException {
      // Given
      MultipartFile bookFile = mockFile("book.pdf");
      byte[] original = {1, 2, 3};
      when(bookFile.getBytes()).thenReturn(original);
      when(bookPreviewGenerator.generatePdfPreview(original, 10))
          .thenReturn(new BookPreviewResult(new byte[] {1}, 10));

      // When / Then
      assertThatThrownBy(
              () ->
                  ingestionService.ingest(
                      AUTHOR_ID, POST_ID, bookRequest(1000L, 10), bookFile, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("must be less than the book's total");
      verify(bookRepository, never()).save(any());
      // B11: nothing may reach MinIO on a rejected book — Postgres rolls back, the bucket does not
      verify(bookStorageService, never()).uploadBook(any(), any());
      verify(bookStorageService, never()).uploadCover(any(), any());
      verify(bookStorageService, never()).uploadPreview(any(), any(), any());
      verify(bookStorageService, never()).uploadPreview(any(), any(), anyString());
    }

    @Test
    @DisplayName(
        "should wrap an IOException as a ValidationException when generating the preview fails")
    void shouldThrowValidationException_whenGeneratingPreviewFails() throws IOException {
      // Given
      MultipartFile bookFile = mockFile("book.pdf");
      when(bookFile.getBytes()).thenThrow(new IOException("disk read failure"));

      // When / Then
      assertThatThrownBy(
              () ->
                  ingestionService.ingest(
                      AUTHOR_ID, POST_ID, bookRequest(1000L, 5), bookFile, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("corrupted or not a valid")
          .hasCauseInstanceOf(IOException.class);
    }
  }
}
