package com.socialapp.bookstore.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import com.socialapp.bookstore.dto.CreateBookRequestDto;
import com.socialapp.bookstore.repository.BookPurchaseRepository;
import com.socialapp.bookstore.repository.BookRepository;
import com.socialapp.common.exception.ValidationException;

/**
 * Stage 5 (Experience-based Testing) — Error Guessing technique per ISTQB CTFL v4.0.1 Section
 * 4.5.3, applied to {@link BookService}'s PDF/EPUB processing paths ({@code countPages} and
 * {@code generatePreview}). Unlike the corrupted-*network*-input scenarios already covered for
 * {@code BookStorageService} (MinIO outages), this class targets a corrupted/malformed *book
 * file itself* — the failure originates from {@code PDFBox}/{@code epublib} rejecting the bytes
 * the user uploaded, not from the storage backend.
 *
 * <p>Before this fix, both paths wrapped {@link IOException} in a raw {@link RuntimeException},
 * which fell through {@code GlobalExceptionHandler}'s catch-all to a 500 — a misleading status
 * for what is really a bad client upload. Both now throw {@link ValidationException} (400),
 * consistent with every other "the client's file is invalid" case in this class (e.g. wrong
 * extension, missing preview pages).
 *
 * <p>{@link BookPreviewGenerator} is mocked — no real PDF/EPUB parsing happens; the mock stands
 * in for PDFBox/epublib throwing on genuinely malformed bytes.
 */
@ExtendWith(MockitoExtension.class)
class BookServiceErrorGuessingTest {

  private static final Integer AUTHOR_ID = 1;
  private static final Integer POST_ID = 100;

  @Mock private BookRepository bookRepository;
  @Mock private BookPurchaseRepository purchaseRepository;
  @Mock private BookStorageService bookStorageService;
  @Mock private BookPreviewGenerator bookPreviewGenerator;

  @InjectMocks private BookService bookService;

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
  // Malformed PDF/EPUB content — free books (countPages path)
  // =====================================================================

  @Nested
  @DisplayName("Malformed book file — page/chapter counting")
  class CountPagesTests {

    @Test
    @DisplayName("shouldThrowValidationException_whenPdfBytesAreMalformed")
    void shouldThrowValidationException_whenPdfBytesAreMalformed() throws IOException {
      // Given — PDFBox rejects the bytes as not a valid PDF (e.g. a renamed .txt file)
      MultipartFile bookFile = mockFile("book.pdf");
      byte[] garbage = {0, 1, 2};
      when(bookStorageService.uploadBook(AUTHOR_ID, bookFile)).thenReturn("book-key");
      when(bookFile.getBytes()).thenReturn(garbage);
      when(bookPreviewGenerator.countPdfPages(garbage))
          .thenThrow(new IOException("Error: Header doesn't contain versioninfo"));

      // When / Then — a corrupted upload must not surface as an opaque 500
      assertThatThrownBy(
              () ->
                  bookService.createBookForPost(
                      AUTHOR_ID, POST_ID, bookRequest(null, null), bookFile, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("corrupted or not a valid")
          .hasCauseInstanceOf(IOException.class);
      verify(bookRepository, never()).save(any());
    }

    @Test
    @DisplayName("shouldThrowValidationException_whenEpubBytesAreMalformed")
    void shouldThrowValidationException_whenEpubBytesAreMalformed() throws IOException {
      // Given — epublib rejects the bytes as not a valid EPUB archive
      MultipartFile bookFile = mockFile("book.epub");
      byte[] garbage = {0, 1, 2};
      when(bookStorageService.uploadBook(AUTHOR_ID, bookFile)).thenReturn("book-key");
      when(bookFile.getBytes()).thenReturn(garbage);
      when(bookPreviewGenerator.countEpubChapters(garbage))
          .thenThrow(new IOException("Not a valid ZIP/EPUB archive"));

      // When / Then
      assertThatThrownBy(
              () ->
                  bookService.createBookForPost(
                      AUTHOR_ID, POST_ID, bookRequest(0L, null), bookFile, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("corrupted or not a valid")
          .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("shouldPreserveOriginalCauseChain_whenPageCountingFails")
    void shouldPreserveOriginalCauseChain_whenPageCountingFails() throws IOException {
      // Given — diagnosability: the original PDFBox/epublib exception must not be swallowed
      MultipartFile bookFile = mockFile("book.pdf");
      byte[] garbage = {0, 1, 2};
      IOException original = new IOException("Header doesn't contain versioninfo");
      when(bookStorageService.uploadBook(AUTHOR_ID, bookFile)).thenReturn("book-key");
      when(bookFile.getBytes()).thenReturn(garbage);
      when(bookPreviewGenerator.countPdfPages(garbage)).thenThrow(original);

      // When / Then
      assertThatThrownBy(
              () ->
                  bookService.createBookForPost(
                      AUTHOR_ID, POST_ID, bookRequest(null, null), bookFile, null))
          .isInstanceOf(ValidationException.class)
          .cause()
          .isSameAs(original);
    }
  }

  // =====================================================================
  // Malformed PDF/EPUB content — paid books (generatePreview path)
  // =====================================================================

  @Nested
  @DisplayName("Malformed book file — preview generation")
  class GeneratePreviewTests {

    @Test
    @DisplayName("shouldThrowValidationException_whenPdfPreviewGenerationFailsOnMalformedBytes")
    void shouldThrowValidationException_whenPdfPreviewGenerationFailsOnMalformedBytes()
        throws IOException {
      // Given — the file passes the extension check but PDFBox can't actually parse it
      MultipartFile bookFile = mockFile("book.pdf");
      byte[] garbage = {0, 1, 2};
      when(bookStorageService.uploadBook(AUTHOR_ID, bookFile)).thenReturn("book-key");
      when(bookFile.getBytes()).thenReturn(garbage);
      when(bookPreviewGenerator.generatePdfPreview(garbage, 5))
          .thenThrow(new IOException("Error: End-of-File, expected line"));

      // When / Then
      assertThatThrownBy(
              () ->
                  bookService.createBookForPost(
                      AUTHOR_ID, POST_ID, bookRequest(1000L, 5), bookFile, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("corrupted or not a valid")
          .hasCauseInstanceOf(IOException.class);
      verify(bookStorageService, never()).uploadPreview(any(), any(), any());
    }

    @Test
    @DisplayName("shouldThrowValidationException_whenEpubPreviewGenerationFailsOnMalformedBytes")
    void shouldThrowValidationException_whenEpubPreviewGenerationFailsOnMalformedBytes()
        throws IOException {
      // Given
      MultipartFile bookFile = mockFile("book.epub");
      byte[] garbage = {0, 1, 2};
      when(bookStorageService.uploadBook(AUTHOR_ID, bookFile)).thenReturn("book-key");
      when(bookFile.getBytes()).thenReturn(garbage);
      when(bookPreviewGenerator.generateEpubPreview(garbage, 3))
          .thenThrow(new IOException("Not a valid ZIP/EPUB archive"));

      // When / Then
      assertThatThrownBy(
              () ->
                  bookService.createBookForPost(
                      AUTHOR_ID, POST_ID, bookRequest(1000L, 3), bookFile, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("corrupted or not a valid")
          .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("shouldNotThrowNullPointerException_whenPreviewGenerationExceptionHasNullMessage")
    void shouldNotThrowNullPointerException_whenPreviewGenerationExceptionHasNullMessage()
        throws IOException {
      // Given — a worst-case third-party exception carrying no message at all
      MultipartFile bookFile = mockFile("book.pdf");
      byte[] garbage = {0, 1, 2};
      when(bookStorageService.uploadBook(AUTHOR_ID, bookFile)).thenReturn("book-key");
      when(bookFile.getBytes()).thenReturn(garbage);
      when(bookPreviewGenerator.generatePdfPreview(garbage, 5))
          .thenThrow(new IOException((String) null));

      // When / Then — the service's own wrapping message must still be intact, no NPE
      assertThatThrownBy(
              () ->
                  bookService.createBookForPost(
                      AUTHOR_ID, POST_ID, bookRequest(1000L, 5), bookFile, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("corrupted or not a valid");
    }
  }
}
