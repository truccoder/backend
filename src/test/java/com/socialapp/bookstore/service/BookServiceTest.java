package com.socialapp.bookstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.bookstore.dto.BookPageResponseDto;
import com.socialapp.bookstore.dto.BookResponseDto;
import com.socialapp.bookstore.dto.CreateBookRequestDto;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.entity.enums.PaymentStatus;
import com.socialapp.bookstore.repository.BookPurchaseRepository;
import com.socialapp.bookstore.repository.BookRepository;
import com.socialapp.common.enums.LearningCategory;
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
  @Mock private BookIngestionService bookIngestionService;

  /**
   * A <b>real</b> mapper over the same mocks, not a mock of it. The download-vs-preview rule these
   * tests assert moved into {@link BookResponseMapper}; mocking it would leave those assertions
   * checking a stub instead of the rule.
   */
  private BookResponseMapper bookResponseMapper;

  private BookService bookService;

  @BeforeEach
  void wireService() {
    bookResponseMapper = new BookResponseMapper(purchaseRepository, bookStorageService);
    bookService =
        new BookService(
            bookRepository,
            purchaseRepository,
            bookStorageService,
            bookIngestionService,
            bookResponseMapper);
  }

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

    @Test
    @DisplayName("should refuse to delete a book somebody has paid for")
    void shouldThrowValidationException_whenBookHasCompletedPurchase() {
      // Given — t_book_purchases cascades on book_id, so this delete would erase the buyer's
      // payment record along with the book they paid for.
      BookEntity soldBook = book(BOOK_ID, AUTHOR_ID, false, "file-key", "preview-key");
      when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(soldBook));
      when(purchaseRepository.existsByBookIdAndPaymentStatus(BOOK_ID, PaymentStatus.COMPLETED))
          .thenReturn(true);

      // When / Then
      assertThatThrownBy(() -> bookService.deleteBook(AUTHOR_ID, BOOK_ID))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("has been purchased");
      verify(bookRepository, never()).delete(any());
      verify(bookStorageService, never()).deleteQuietly(any(), any());
    }

    @Test
    @DisplayName("should remove the book's file, preview and cover from storage")
    void shouldDeleteStorageObjects_whenBookIsDeleted() {
      // Given
      BookEntity ownBook =
          BookEntity.builder()
              .id(BOOK_ID)
              .authorId(AUTHOR_ID)
              .isFree(false)
              .fileKey("file-key")
              .previewFileKey("preview-key")
              .coverImageKey("cover-key")
              .build();
      when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(ownBook));
      when(bookStorageService.booksBucket()).thenReturn("books");
      when(bookStorageService.coversBucket()).thenReturn("book-covers");

      // When
      bookService.deleteBook(AUTHOR_ID, BOOK_ID);

      // Then — leaving these behind is how the bucket filled up with files no row points at.
      verify(bookStorageService).deleteQuietly("books", "file-key");
      verify(bookStorageService).deleteQuietly("books", "preview-key");
      verify(bookStorageService).deleteQuietly("book-covers", "cover-key");
    }
  }

  // =====================================================================
  // deleteBooksForPost
  // =====================================================================

  @Nested
  @DisplayName("deleteBooksForPost")
  class DeleteBooksForPostTests {

    @Test
    @DisplayName("should delete the book attached to the post")
    void shouldDeleteAttachedBook() {
      // Given
      BookEntity attached = book(BOOK_ID, AUTHOR_ID, true, "file-key", null);
      when(bookRepository.findByPostId(POST_ID)).thenReturn(List.of(attached));

      // When
      bookService.deleteBooksForPost(POST_ID);

      // Then — otherwise the FK's ON DELETE SET NULL leaves it orphaned once the post goes.
      verify(bookRepository).delete(attached);
    }

    @Test
    @DisplayName("should do nothing when the post has no book")
    void shouldDoNothing_whenPostHasNoBook() {
      // Given
      when(bookRepository.findByPostId(POST_ID)).thenReturn(List.of());

      // When
      bookService.deleteBooksForPost(POST_ID);

      // Then
      verify(bookRepository, never()).delete(any());
    }

    @Test
    @DisplayName("should refuse when the attached book has been sold")
    void shouldThrowValidationException_whenAttachedBookIsSold() {
      // Given
      BookEntity sold = book(BOOK_ID, AUTHOR_ID, false, "file-key", "preview-key");
      when(bookRepository.findByPostId(POST_ID)).thenReturn(List.of(sold));
      when(purchaseRepository.existsByBookIdAndPaymentStatus(BOOK_ID, PaymentStatus.COMPLETED))
          .thenReturn(true);

      // When / Then — this is what stops the enclosing deletePost from going through.
      assertThatThrownBy(() -> bookService.deleteBooksForPost(POST_ID))
          .isInstanceOf(ValidationException.class);
      verify(bookRepository, never()).delete(any());
    }
  }

  // =====================================================================
  // getLibraryPage  (D2 — the Library front page)
  // =====================================================================

  @Nested
  @DisplayName("getLibraryPage")
  class GetLibraryPageTests {

    @Test
    @DisplayName("should trim the look-ahead row and report hasMore")
    void shouldTrimLookaheadRow() {
      // Given: the repository is asked for limit + 1 so hasMore costs no COUNT(*) per scroll
      BookEntity b30 = new BookEntity();
      b30.setId(30);
      b30.setAuthorId(1);
      b30.setIsFree(true);
      BookEntity b29 = new BookEntity();
      b29.setId(29);
      b29.setAuthorId(1);
      b29.setIsFree(true);
      BookEntity b28 = new BookEntity();
      b28.setId(28);
      b28.setAuthorId(1);
      b28.setIsFree(true);
      when(bookRepository.findLibraryPage(any(), any(), any())).thenReturn(List.of(b30, b29, b28));

      // When
      BookPageResponseDto result = bookService.getLibraryPage(null, 2, null, 1);

      // Then
      assertThat(result.items()).hasSize(2);
      assertThat(result.hasMore()).isTrue();
      assertThat(result.nextCursor()).isEqualTo(29);
    }

    @Test
    @DisplayName("should return a null cursor and hasMore=false for an empty library")
    void shouldHandleEmptyPage() {
      // Given
      when(bookRepository.findLibraryPage(any(), any(), any())).thenReturn(List.of());

      // When
      BookPageResponseDto result = bookService.getLibraryPage(null, 10, null, 1);

      // Then
      assertThat(result.items()).isEmpty();
      assertThat(result.nextCursor()).isNull();
      assertThat(result.hasMore()).isFalse();
    }

    @Test
    @DisplayName("should hand the category to the query rather than filter the page after it")
    void shouldPushCategoryDownToTheQuery() {
      // Given: trang chỉ có tối đa 50 hàng, nên lọc sau khi cắt trang là lọc trên một mẫu — một
      // chủ đề có sách nhưng không có cuốn nào ở trang đầu sẽ hiện ra rỗng. Bằng chứng duy nhất
      // ở tầng này là câu hỏi gửi xuống repository đã mang theo chủ đề.
      when(bookRepository.findLibraryPage(any(), any(), any())).thenReturn(List.of());

      // When
      bookService.getLibraryPage(null, 10, LearningCategory.MOBILE, 1);

      // Then
      verify(bookRepository).findLibraryPage(isNull(), eq(LearningCategory.MOBILE), any());
    }
  }

  // =====================================================================
  // getPurchasedPage  (FE docs/backend-plan.md B37 — the "Sách đã mua" tab)
  // =====================================================================

  @Nested
  @DisplayName("getPurchasedPage")
  class GetPurchasedPageTests {

    @Test
    @DisplayName("should trim the look-ahead row and report hasMore")
    void shouldTrimLookaheadRow() {
      // Given
      BookEntity b30 = new BookEntity();
      b30.setId(30);
      b30.setAuthorId(1);
      b30.setIsFree(false);
      BookEntity b29 = new BookEntity();
      b29.setId(29);
      b29.setAuthorId(1);
      b29.setIsFree(false);
      BookEntity b28 = new BookEntity();
      b28.setId(28);
      b28.setAuthorId(1);
      b28.setIsFree(false);
      when(bookRepository.findPurchasedPage(any(), any(), any()))
          .thenReturn(List.of(b30, b29, b28));

      // When
      BookPageResponseDto result = bookService.getPurchasedPage(OTHER_USER_ID, null, 2);

      // Then
      assertThat(result.items()).hasSize(2);
      assertThat(result.hasMore()).isTrue();
      assertThat(result.nextCursor()).isEqualTo(29);
    }

    @Test
    @DisplayName("should return a null cursor and hasMore=false when nothing was purchased")
    void shouldHandleEmptyPage() {
      // Given
      when(bookRepository.findPurchasedPage(any(), any(), any())).thenReturn(List.of());

      // When
      BookPageResponseDto result = bookService.getPurchasedPage(OTHER_USER_ID, null, 10);

      // Then
      assertThat(result.items()).isEmpty();
      assertThat(result.nextCursor()).isNull();
      assertThat(result.hasMore()).isFalse();
    }

    @Test
    @DisplayName("should scope the query to the calling buyer and pass the cursor through")
    void shouldScopeToBuyerAndPassCursorThrough() {
      // Given
      when(bookRepository.findPurchasedPage(any(), any(), any())).thenReturn(List.of());

      // When
      bookService.getPurchasedPage(OTHER_USER_ID, 20, 10);

      // Then
      verify(bookRepository).findPurchasedPage(eq(OTHER_USER_ID), eq(20), any());
    }
  }
}
