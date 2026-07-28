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
import java.util.List;
import java.util.Optional;

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

import com.socialapp.bookstore.dto.BookResponseDto;
import com.socialapp.bookstore.dto.CreateBookRequestDto;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.entity.enums.FileFormat;
import com.socialapp.bookstore.entity.enums.PaymentStatus;
import com.socialapp.bookstore.repository.BookPurchaseRepository;
import com.socialapp.bookstore.repository.BookRepository;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;

/**
 * Component (unit) tests for {@link BookService}, per ISTQB CTFL v4.0.1:
 *
 * <ul>
 *   <li><b>Component testing</b> (Section 2.2.1) — {@code BookRepository}, {@code
 *       BookPurchaseRepository}, {@code BookStorageService} and {@code BookPreviewGenerator} are
 *       all mocked with Mockito ({@code @ExtendWith(MockitoExtension.class)}, no Spring context).
 *   <li><b>Test Pyramid</b> (Section 5.1.6) — fast, isolated, bottom-layer tests.
 *   <li><b>Branch testing / branch coverage</b> (Section 4.3.2, white-box) — inputs are chosen
 *       with knowledge of BookService's if/else and short-circuit ({@code &&}/{@code ||})
 *       structure so every control-flow-graph branch is driven to both true and false.
 *   <li><b>BDD Given/When/Then</b> (Section 2.1.3).
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class BookServiceTest {

  private static final Integer AUTHOR_ID = 1;
  private static final Integer OTHER_USER_ID = 2;
  private static final Integer POST_ID = 100;
  private static final Integer BOOK_ID = 50;

  @Mock private BookRepository bookRepository;
  @Mock private BookPurchaseRepository purchaseRepository;
  @Mock private BookStorageService bookStorageService;
  @Mock private BookPreviewGenerator bookPreviewGenerator;

  @InjectMocks private BookService bookService;

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

  private static BookEntity book(
      Integer id, Integer authorId, boolean isFree, String fileKey, String previewFileKey) {
    return BookEntity.builder()
        .id(id)
        .authorId(authorId)
        .isFree(isFree)
        .fileKey(fileKey)
        .previewFileKey(previewFileKey)
        .downloadCount(3)
        .build();
  }

  // =====================================================================
  // createBookForPost
  // =====================================================================

  @Nested
  @DisplayName("createBookForPost")
  class CreateBookForPostTests {

    @Test
    @DisplayName("should reject a null book file before touching any dependency")
    void shouldThrowValidationException_whenBookFileIsNull() {
      // Given / When / Then
      assertThatThrownBy(
              () ->
                  bookService.createBookForPost(
                      AUTHOR_ID, POST_ID, bookRequest(null, null), null, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Book file is required");
      verifyNoInteractions(
          bookRepository, purchaseRepository, bookStorageService, bookPreviewGenerator);
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
                  bookService.createBookForPost(
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
                  bookService.createBookForPost(
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
                  bookService.createBookForPost(
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
                  bookService.createBookForPost(
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
          bookService.createBookForPost(
              AUTHOR_ID, POST_ID, bookRequest(null, null), bookFile, null);

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
      bookService.createBookForPost(
          AUTHOR_ID, POST_ID, bookRequest(null, null), bookFile, coverFile);

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
      bookService.createBookForPost(
          AUTHOR_ID, POST_ID, bookRequest(null, null), bookFile, coverFile);

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
      bookService.createBookForPost(AUTHOR_ID, POST_ID, bookRequest(0L, null), bookFile, null);

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
                  bookService.createBookForPost(
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
                  bookService.createBookForPost(
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
                  bookService.createBookForPost(
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
      bookService.createBookForPost(AUTHOR_ID, POST_ID, bookRequest(1000L, 5), bookFile, null);

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
      bookService.createBookForPost(AUTHOR_ID, POST_ID, bookRequest(1000L, 3), bookFile, null);

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
                  bookService.createBookForPost(
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
                  bookService.createBookForPost(
                      AUTHOR_ID, POST_ID, bookRequest(1000L, 5), bookFile, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("corrupted or not a valid")
          .hasCauseInstanceOf(IOException.class);
    }
  }

  // =====================================================================
  // getBook
  // =====================================================================

  @Nested
  @DisplayName("getBook")
  class GetBookTests {

    @Test
    @DisplayName("should reject when the book does not exist")
    void shouldThrowNotFoundException_whenBookDoesNotExist() {
      // Given
      when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> bookService.getBook(BOOK_ID, AUTHOR_ID))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Book not found");
    }

    @Test
    @DisplayName("should expose a download URL for a free book regardless of requester")
    void shouldReturnDownloadUrl_whenBookIsFree() {
      // Given
      BookEntity freeBook = book(BOOK_ID, AUTHOR_ID, true, "file-key", null);
      when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(freeBook));
      when(bookStorageService.getDownloadUrl("file-key")).thenReturn("https://cdn/file-key");

      // When
      BookResponseDto dto = bookService.getBook(BOOK_ID, null);

      // Then
      assertThat(dto.getDownloadUrl()).isEqualTo("https://cdn/file-key");
      assertThat(dto.getPreviewUrl()).isNull();
      assertThat(dto.getPurchased()).isFalse();
      verifyNoInteractions(purchaseRepository);
    }

    @Test
    @DisplayName("should expose a preview URL, falling back to the file key, when unauthenticated")
    void shouldReturnPreviewUrl_whenRequesterIdIsNull() {
      // Given
      BookEntity paidBook = book(BOOK_ID, AUTHOR_ID, false, "file-key", null);
      when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(paidBook));
      when(bookStorageService.getPreviewUrl("file-key")).thenReturn("https://cdn/preview");

      // When
      BookResponseDto dto = bookService.getBook(BOOK_ID, null);

      // Then
      assertThat(dto.getPreviewUrl()).isEqualTo("https://cdn/preview");
      assertThat(dto.getDownloadUrl()).isNull();
      assertThat(dto.getPurchased()).isFalse();
      verifyNoInteractions(purchaseRepository);
    }

    @Test
    @DisplayName("should expose a download URL when the requester is the book's author")
    void shouldReturnDownloadUrl_whenRequesterIsAuthor() {
      // Given
      BookEntity paidBook = book(BOOK_ID, AUTHOR_ID, false, "file-key", null);
      when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(paidBook));
      when(bookStorageService.getDownloadUrl("file-key")).thenReturn("https://cdn/file-key");

      // When
      BookResponseDto dto = bookService.getBook(BOOK_ID, AUTHOR_ID);

      // Then
      assertThat(dto.getPurchased()).isTrue();
      assertThat(dto.getDownloadUrl()).isEqualTo("https://cdn/file-key");
      verifyNoInteractions(purchaseRepository);
    }

    @Test
    @DisplayName("should expose a download URL when the requester purchased the paid book")
    void shouldReturnDownloadUrl_whenRequesterPurchasedTheBook() {
      // Given
      BookEntity paidBook = book(BOOK_ID, AUTHOR_ID, false, "file-key", null);
      when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(paidBook));
      when(purchaseRepository.existsByBookIdAndBuyerIdAndPaymentStatus(
              BOOK_ID, OTHER_USER_ID, PaymentStatus.COMPLETED))
          .thenReturn(true);
      when(bookStorageService.getDownloadUrl("file-key")).thenReturn("https://cdn/file-key");

      // When
      BookResponseDto dto = bookService.getBook(BOOK_ID, OTHER_USER_ID);

      // Then
      assertThat(dto.getPurchased()).isTrue();
      assertThat(dto.getDownloadUrl()).isEqualTo("https://cdn/file-key");
    }

    @Test
    @DisplayName(
        "should expose a preview URL from the preview file key when the requester has not purchased")
    void shouldReturnPreviewUrl_whenRequesterHasNotPurchased() {
      // Given
      BookEntity paidBook = book(BOOK_ID, AUTHOR_ID, false, "file-key", "preview-key");
      when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(paidBook));
      when(purchaseRepository.existsByBookIdAndBuyerIdAndPaymentStatus(
              BOOK_ID, OTHER_USER_ID, PaymentStatus.COMPLETED))
          .thenReturn(false);
      when(bookStorageService.getPreviewUrl("preview-key")).thenReturn("https://cdn/preview-key");

      // When
      BookResponseDto dto = bookService.getBook(BOOK_ID, OTHER_USER_ID);

      // Then
      assertThat(dto.getPurchased()).isFalse();
      assertThat(dto.getPreviewUrl()).isEqualTo("https://cdn/preview-key");
      assertThat(dto.getDownloadUrl()).isNull();
    }
  }

  // =====================================================================
  // getBooksByAuthor
  // =====================================================================

  @Nested
  @DisplayName("getBooksByAuthor")
  class GetBooksByAuthorTests {

    @Test
    @DisplayName("should return an empty list when the author has no books")
    void shouldReturnEmptyList_whenAuthorHasNoBooks() {
      // Given
      when(bookRepository.findByAuthorIdOrderByCreatedAtDesc(AUTHOR_ID)).thenReturn(List.of());

      // When
      List<BookResponseDto> result = bookService.getBooksByAuthor(AUTHOR_ID, AUTHOR_ID);

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should map every book owned by the author to a response DTO")
    void shouldMapEachBookToResponseDto_whenAuthorHasBooks() {
      // Given
      BookEntity book1 = book(1, AUTHOR_ID, true, "key-1", null);
      BookEntity book2 = book(2, AUTHOR_ID, true, "key-2", null);
      when(bookRepository.findByAuthorIdOrderByCreatedAtDesc(AUTHOR_ID))
          .thenReturn(List.of(book1, book2));
      when(bookStorageService.getDownloadUrl("key-1")).thenReturn("url-1");
      when(bookStorageService.getDownloadUrl("key-2")).thenReturn("url-2");

      // When
      List<BookResponseDto> result = bookService.getBooksByAuthor(AUTHOR_ID, AUTHOR_ID);

      // Then
      assertThat(result).hasSize(2);
      assertThat(result)
          .extracting(BookResponseDto::getDownloadUrl)
          .containsExactly("url-1", "url-2");
    }
  }

  // =====================================================================
  // getFullDownloadUrl
  // =====================================================================

  @Nested
  @DisplayName("getFullDownloadUrl")
  class GetFullDownloadUrlTests {

    @Test
    @DisplayName("should reject when the book does not exist")
    void shouldThrowNotFoundException_whenBookDoesNotExist() {
      // Given
      when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> bookService.getFullDownloadUrl(BOOK_ID, AUTHOR_ID))
          .isInstanceOf(NotFoundException.class);
      verifyNoInteractions(purchaseRepository);
    }

    @Test
    @DisplayName(
        "should increment the download count and return the URL for a free book, skipping the purchase check")
    void shouldIncrementDownloadCountAndReturnUrl_whenBookIsFree() {
      // Given
      BookEntity freeBook = book(BOOK_ID, AUTHOR_ID, true, "file-key", null);
      when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(freeBook));
      when(bookStorageService.getDownloadUrl("file-key")).thenReturn("https://cdn/file-key");

      // When
      String url = bookService.getFullDownloadUrl(BOOK_ID, OTHER_USER_ID);

      // Then
      assertThat(url).isEqualTo("https://cdn/file-key");
      assertThat(freeBook.getDownloadCount()).isEqualTo(4);
      verify(bookRepository).save(freeBook);
      verifyNoInteractions(purchaseRepository);
    }

    @Test
    @DisplayName(
        "should increment the download count for the author of a paid book without a purchase check")
    void shouldIncrementDownloadCountAndReturnUrl_whenPaidBookRequestedByAuthor() {
      // Given
      BookEntity paidBook = book(BOOK_ID, AUTHOR_ID, false, "file-key", null);
      when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(paidBook));
      when(bookStorageService.getDownloadUrl("file-key")).thenReturn("https://cdn/file-key");

      // When
      String url = bookService.getFullDownloadUrl(BOOK_ID, AUTHOR_ID);

      // Then
      assertThat(url).isEqualTo("https://cdn/file-key");
      verifyNoInteractions(purchaseRepository);
    }

    @Test
    @DisplayName(
        "should increment the download count when a non-author has purchased the paid book")
    void shouldIncrementDownloadCountAndReturnUrl_whenPaidBookPurchasedByUser() {
      // Given
      BookEntity paidBook = book(BOOK_ID, AUTHOR_ID, false, "file-key", null);
      when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(paidBook));
      when(purchaseRepository.existsByBookIdAndBuyerIdAndPaymentStatus(
              BOOK_ID, OTHER_USER_ID, PaymentStatus.COMPLETED))
          .thenReturn(true);
      when(bookStorageService.getDownloadUrl("file-key")).thenReturn("https://cdn/file-key");

      // When
      String url = bookService.getFullDownloadUrl(BOOK_ID, OTHER_USER_ID);

      // Then
      assertThat(url).isEqualTo("https://cdn/file-key");
      assertThat(paidBook.getDownloadCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("should reject a non-author who has not purchased the paid book")
    void shouldThrowForbiddenException_whenPaidBookNotPurchased() {
      // Given
      BookEntity paidBook = book(BOOK_ID, AUTHOR_ID, false, "file-key", null);
      when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(paidBook));
      when(purchaseRepository.existsByBookIdAndBuyerIdAndPaymentStatus(
              BOOK_ID, OTHER_USER_ID, PaymentStatus.COMPLETED))
          .thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> bookService.getFullDownloadUrl(BOOK_ID, OTHER_USER_ID))
          .isInstanceOf(ForbiddenException.class)
          .hasMessageContaining("You must purchase this book before downloading");
      verify(bookRepository, never()).save(any());
      // B11: nothing may reach MinIO on a rejected book — Postgres rolls back, the bucket does not
      verify(bookStorageService, never()).uploadBook(any(), any());
      verify(bookStorageService, never()).uploadCover(any(), any());
      verify(bookStorageService, never()).uploadPreview(any(), any(), any());
    }
  }

  // =====================================================================
  // getPreviewUrl
  // =====================================================================

  @Nested
  @DisplayName("getPreviewUrl")
  class GetPreviewUrlTests {

    @Test
    @DisplayName("should reject when the book does not exist")
    void shouldThrowNotFoundException_whenBookDoesNotExist() {
      // Given
      when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> bookService.getPreviewUrl(BOOK_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should use the dedicated preview file key when present")
    void shouldUsePreviewFileKey_whenPresent() {
      // Given
      BookEntity paidBook = book(BOOK_ID, AUTHOR_ID, false, "file-key", "preview-key");
      when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(paidBook));
      when(bookStorageService.getPreviewUrl("preview-key")).thenReturn("https://cdn/preview-key");

      // When
      String url = bookService.getPreviewUrl(BOOK_ID);

      // Then
      assertThat(url).isEqualTo("https://cdn/preview-key");
    }

    @Test
    @DisplayName("should fall back to the full file key when no preview file key exists")
    void shouldFallBackToFileKey_whenPreviewFileKeyIsNull() {
      // Given
      BookEntity freeBook = book(BOOK_ID, AUTHOR_ID, true, "file-key", null);
      when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(freeBook));
      when(bookStorageService.getPreviewUrl("file-key")).thenReturn("https://cdn/file-key");

      // When
      String url = bookService.getPreviewUrl(BOOK_ID);

      // Then
      assertThat(url).isEqualTo("https://cdn/file-key");
    }
  }

  // =====================================================================
  // deleteBook
  // =====================================================================

  @Nested
  @DisplayName("deleteBook")
  class DeleteBookTests {

    @Test
    @DisplayName("should reject when the book does not exist")
    void shouldThrowNotFoundException_whenBookDoesNotExist() {
      // Given
      when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> bookService.deleteBook(AUTHOR_ID, BOOK_ID))
          .isInstanceOf(NotFoundException.class);
      verify(bookRepository, never()).delete(any());
    }

    @Test
    @DisplayName("should reject when the actor is not the book's author")
    void shouldThrowForbiddenException_whenActorIsNotAuthor() {
      // Given
      BookEntity someoneElsesBook = book(BOOK_ID, AUTHOR_ID, true, "file-key", null);
      when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(someoneElsesBook));

      // When / Then
      assertThatThrownBy(() -> bookService.deleteBook(OTHER_USER_ID, BOOK_ID))
          .isInstanceOf(ForbiddenException.class)
          .hasMessageContaining("Only the author can delete this book");
      verify(bookRepository, never()).delete(any());
    }

    @Test
    @DisplayName("should delete the book when the actor is its author")
    void shouldDeleteBook_whenActorIsAuthor() {
      // Given
      BookEntity ownBook = book(BOOK_ID, AUTHOR_ID, true, "file-key", null);
      when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(ownBook));

      // When
      bookService.deleteBook(AUTHOR_ID, BOOK_ID);

      // Then
      verify(bookRepository).delete(ownBook);
    }
  }
}
