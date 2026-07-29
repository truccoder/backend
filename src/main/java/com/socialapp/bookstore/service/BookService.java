package com.socialapp.bookstore.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.bookstore.dto.BookResponseDto;
import com.socialapp.bookstore.dto.CreateBookRequestDto;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.entity.enums.PaymentStatus;
import com.socialapp.bookstore.repository.BookPurchaseRepository;
import com.socialapp.bookstore.repository.BookRepository;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Everything a book does once it exists: reads, signed URLs, purchase checks, deletion. Creation
 * lives in {@link BookIngestionService} and is fronted here so callers keep one entry point.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookService {
  private final BookRepository bookRepository;
  private final BookPurchaseRepository purchaseRepository;
  private final BookStorageService bookStorageService;
  private final BookIngestionService bookIngestionService;

  /**
   * Creates a book already linked to a post. This is the only way to create a book — always
   * called from {@code PostService.createBookPost} with the post's freshly generated ID, so a
   * book can never exist without a post to belong to.
   */
  @Transactional
  public BookEntity createBookForPost(
      Integer authorId,
      Integer postId,
      CreateBookRequestDto bookDetails,
      MultipartFile bookFile,
      MultipartFile coverFile) {
    return bookIngestionService.ingest(authorId, postId, bookDetails, bookFile, coverFile);
  }

  public BookResponseDto getBook(Integer bookId, Integer requesterId) {
    BookEntity book = findBookOrThrow(bookId);
    return toResponseDto(book, requesterId);
  }

  public List<BookResponseDto> getBooksByAuthor(Integer authorId, Integer requesterId) {
    return bookRepository.findByAuthorIdOrderByCreatedAtDesc(authorId).stream()
        .map(book -> toResponseDto(book, requesterId))
        .toList();
  }

  public String getFullDownloadUrl(Integer bookId, Integer userId) {
    BookEntity book = findBookOrThrow(bookId);

    if (!book.getIsFree() && !book.getAuthorId().equals(userId)) {
      boolean purchased =
          purchaseRepository.existsByBookIdAndBuyerIdAndPaymentStatus(
              bookId, userId, PaymentStatus.COMPLETED);
      if (!purchased) {
        throw new ForbiddenException("You must purchase this book before downloading");
      }
    }

    book.setDownloadCount(book.getDownloadCount() + 1);
    bookRepository.save(book);
    return bookStorageService.getDownloadUrl(book.getFileKey());
  }

  public String getPreviewUrl(Integer bookId) {
    BookEntity book = findBookOrThrow(bookId);
    return bookStorageService.getPreviewUrl(previewKeyOrFallback(book));
  }

  private String previewKeyOrFallback(BookEntity book) {
    return book.getPreviewFileKey() != null ? book.getPreviewFileKey() : book.getFileKey();
  }

  @Transactional
  public void deleteBook(Integer authorId, Integer bookId) {
    BookEntity book = findBookOrThrow(bookId);
    if (!book.getAuthorId().equals(authorId)) {
      throw new ForbiddenException("Only the author can delete this book");
    }
    deleteBookAndAssets(book);
  }

  /**
   * Removes the book(s) attached to a post, for {@code PostService.deletePost}.
   *
   * <p>Without this the post went away and the book row stayed behind with {@code post_id} nulled
   * by the FK's ON DELETE SET NULL — reachable through no post, listed by nothing, with its files
   * kept in MinIO forever. Refusing the delete for a sold book (below) means deleting such a post
   * now fails as a whole, which is the point: the post is the only page the book has.
   */
  @Transactional
  public void deleteBooksForPost(Integer postId) {
    bookRepository.findByPostId(postId).forEach(this::deleteBookAndAssets);
  }

  /**
   * The single delete path for a book: refuse if it has been sold, otherwise drop the row and the
   * files behind it.
   */
  private void deleteBookAndAssets(BookEntity book) {
    // t_book_purchases.book_id is ON DELETE CASCADE, so deleting a sold book would erase the
    // buyers' payment records along with the only copy of what they paid for — silently, with no
    // refund and no trace of the transaction. An author who wants a sold book gone has to go
    // through support, not through this endpoint.
    if (purchaseRepository.existsByBookIdAndPaymentStatus(book.getId(), PaymentStatus.COMPLETED)) {
      throw new ValidationException("This book has been purchased and can no longer be deleted");
    }

    bookRepository.delete(book);

    // Storage last, and quietly: MinIO is not in the transaction, so a failure here must not undo
    // a valid delete. The residual risk is the opposite order — a rollback after this point would
    // leave a row pointing at deleted objects — but nothing runs after this in the delete paths,
    // and leaking a file on every failed delete (the old behaviour) is the worse of the two.
    bookStorageService.deleteQuietly(bookStorageService.booksBucket(), book.getFileKey());
    bookStorageService.deleteQuietly(bookStorageService.booksBucket(), book.getPreviewFileKey());
    bookStorageService.deleteQuietly(bookStorageService.coversBucket(), book.getCoverImageKey());
  }

  BookEntity findBookOrThrow(Integer bookId) {
    return bookRepository
        .findById(bookId)
        .orElseThrow(() -> new NotFoundException("Book not found: " + bookId));
  }

  private BookResponseDto toResponseDto(BookEntity book, Integer requesterId) {
    boolean purchased =
        !book.getIsFree()
            && requesterId != null
            && (book.getAuthorId().equals(requesterId)
                || purchaseRepository.existsByBookIdAndBuyerIdAndPaymentStatus(
                    book.getId(), requesterId, PaymentStatus.COMPLETED));

    String downloadUrl = null;
    String previewUrl = null;

    if (book.getIsFree() || purchased) {
      downloadUrl = bookStorageService.getDownloadUrl(book.getFileKey());
    } else {
      previewUrl = bookStorageService.getPreviewUrl(previewKeyOrFallback(book));
    }

    return BookResponseDto.builder()
        .id(book.getId())
        .authorId(book.getAuthorId())
        .postId(book.getPostId())
        .title(book.getTitle())
        .description(book.getDescription())
        .downloadUrl(downloadUrl)
        .previewUrl(previewUrl)
        .coverImageUrl(bookStorageService.getCoverUrl(book.getCoverImageKey()))
        .fileFormat(book.getFileFormat())
        .fileSizeBytes(book.getFileSizeBytes())
        .totalPages(book.getTotalPages())
        .previewPages(book.getPreviewPages())
        .price(book.getPrice())
        .currency(book.getCurrency())
        .isFree(book.getIsFree())
        .downloadCount(book.getDownloadCount())
        .avgRating(book.getAvgRating())
        .reviewCount(book.getReviewCount())
        .purchased(purchased)
        .createdAt(book.getCreatedAt())
        .build();
  }
}
