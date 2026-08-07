package com.socialapp.bookstore.service;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.bookstore.dto.BookPageResponseDto;
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
  private final BookResponseMapper bookResponseMapper;

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
    return bookResponseMapper.toResponseDto(book, requesterId);
  }

  /**
   * One cursor page of the library, newest first.
   *
   * <p>No visibility filter, deliberately: a book is a catalogue entry, not a post. What a
   * non-buyer may actually <em>do</em> with it is decided per row in {@link #toResponseDto}, which
   * hands back a preview URL instead of a download URL — so listing every book exposes titles and
   * covers, which is the point of a store front, and nothing more.
   *
   * <p>Fetches one row beyond {@code limit} so {@code hasMore} is answered by the page itself
   * rather than by a {@code COUNT(*)} over the whole table on every scroll.
   */
  public BookPageResponseDto getLibraryPage(Integer cursor, int limit, Integer requesterId) {
    List<BookEntity> page = bookRepository.findLibraryPage(cursor, PageRequest.of(0, limit + 1));

    boolean hasMore = page.size() > limit;
    List<BookEntity> visible = hasMore ? page.subList(0, limit) : page;

    List<BookResponseDto> items =
        visible.stream().map(book -> bookResponseMapper.toResponseDto(book, requesterId)).toList();
    Integer nextCursor = visible.isEmpty() ? null : visible.get(visible.size() - 1).getId();

    return new BookPageResponseDto(items, nextCursor, hasMore);
  }

  public List<BookResponseDto> getBooksByAuthor(Integer authorId, Integer requesterId) {
    return bookRepository.findByAuthorIdOrderByCreatedAtDesc(authorId).stream()
        .map(book -> bookResponseMapper.toResponseDto(book, requesterId))
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
    return bookStorageService.getPreviewUrl(bookResponseMapper.previewKeyOrFallback(book));
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
}
