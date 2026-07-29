package com.socialapp.bookstore.service;

import java.io.IOException;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import com.socialapp.common.utils.FileExtensions;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookService {
  private final BookRepository bookRepository;
  private final BookPurchaseRepository purchaseRepository;
  private final BookStorageService bookStorageService;
  private final BookPreviewGenerator bookPreviewGenerator;

  private static final List<String> ALLOWED_FORMATS = List.of("pdf", "epub");

  // Carries the bytes rather than an object key: generating a preview must not have
  // uploaded anything yet, since this is the step that can still reject the request.
  private record GeneratedPreview(byte[] previewBytes, int totalUnits) {}

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
    return buildAndSaveBook(authorId, postId, bookDetails, bookFile, coverFile);
  }

  private BookEntity buildAndSaveBook(
      Integer authorId,
      Integer postId,
      CreateBookRequestDto request,
      MultipartFile bookFile,
      MultipartFile coverFile) {
    // Everything that can reject the request runs before the first byte reaches MinIO. Uploads
    // used to come first, and because MinIO is not part of the surrounding transaction, a book
    // rejected for its previewPages left its file (and cover) in the bucket forever while
    // Postgres rolled back cleanly — measured at 4 objects for 2 books.
    validateFile(bookFile);

    FileFormat format = resolveFormat(bookFile.getOriginalFilename());
    boolean isFree = request.getPrice() == null || request.getPrice() <= 0;

    if (!isFree && (request.getPreviewPages() == null || request.getPreviewPages() <= 0)) {
      throw new ValidationException("Paid books must have preview pages configured");
    }

    Integer totalPages;
    byte[] previewBytes = null;

    if (isFree) {
      totalPages = countPages(format, bookFile);
    } else {
      // Generated in memory here, uploaded below: this is the step that rejects a previewPages
      // value larger than the book itself, and it must be able to do so with nothing uploaded.
      GeneratedPreview preview = generatePreview(format, bookFile, request.getPreviewPages());
      previewBytes = preview.previewBytes();
      totalPages = preview.totalUnits();
    }

    // Past this line uploads begin. Validation cannot fail any more, but MinIO or the insert
    // still can, so whatever landed is removed on the way out.
    String fileKey = null;
    String previewFileKey = null;
    String coverKey = null;

    try {
      fileKey = bookStorageService.uploadBook(authorId, bookFile);

      if (previewBytes != null) {
        String extension = format == FileFormat.EPUB ? "epub" : "pdf";
        previewFileKey = bookStorageService.uploadPreview(authorId, previewBytes, extension);
      }

      if (coverFile != null && !coverFile.isEmpty()) {
        coverKey = bookStorageService.uploadCover(authorId, coverFile);
      }

      return saveBook(
          authorId,
          postId,
          request,
          bookFile,
          format,
          isFree,
          fileKey,
          previewFileKey,
          coverKey,
          totalPages);
    } catch (RuntimeException e) {
      bookStorageService.deleteQuietly(bookStorageService.booksBucket(), fileKey);
      bookStorageService.deleteQuietly(bookStorageService.booksBucket(), previewFileKey);
      bookStorageService.deleteQuietly(bookStorageService.coversBucket(), coverKey);
      throw e;
    }
  }

  private BookEntity saveBook(
      Integer authorId,
      Integer postId,
      CreateBookRequestDto request,
      MultipartFile bookFile,
      FileFormat format,
      boolean isFree,
      String fileKey,
      String previewFileKey,
      String coverKey,
      Integer totalPages) {
    BookEntity book =
        BookEntity.builder()
            .authorId(authorId)
            .postId(postId)
            .title(request.getTitle())
            .description(request.getDescription())
            .fileKey(fileKey)
            .previewFileKey(previewFileKey)
            .coverImageKey(coverKey)
            .fileFormat(format)
            .fileSizeBytes(bookFile.getSize())
            .totalPages(totalPages)
            .previewPages(isFree ? 0 : request.getPreviewPages())
            .price(isFree ? 0L : request.getPrice())
            .isFree(isFree)
            .build();

    bookRepository.save(book);
    return book;
  }

  private int countPages(FileFormat format, MultipartFile bookFile) {
    try {
      byte[] original = bookFile.getBytes();
      return format == FileFormat.EPUB
          ? bookPreviewGenerator.countEpubChapters(original)
          : bookPreviewGenerator.countPdfPages(original);
    } catch (IOException e) {
      throw new ValidationException("Book file is corrupted or not a valid " + format, e);
    }
  }

  /**
   * Generates and uploads a trimmed preview containing only the first {@code previewPages}
   * pages/chapters, so previewing a paid book can never expose more than that regardless of what
   * URL the client has.
   */
  private GeneratedPreview generatePreview(
      FileFormat format, MultipartFile bookFile, int previewPages) {
    try {
      byte[] original = bookFile.getBytes();
      BookPreviewResult result =
          format == FileFormat.EPUB
              ? bookPreviewGenerator.generateEpubPreview(original, previewPages)
              : bookPreviewGenerator.generatePdfPreview(original, previewPages);

      if (previewPages >= result.totalUnits()) {
        throw new ValidationException(
            "Preview pages/chapters ("
                + previewPages
                + ") must be less than the book's total ("
                + result.totalUnits()
                + ")");
      }

      return new GeneratedPreview(result.previewBytes(), result.totalUnits());
    } catch (IOException e) {
      throw new ValidationException("Book file is corrupted or not a valid " + format, e);
    }
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

  private void validateFile(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new ValidationException("Book file is required");
    }
    String ext = FileExtensions.getExtension(file.getOriginalFilename(), "");
    if (!ALLOWED_FORMATS.contains(ext)) {
      throw new ValidationException("Only PDF and EPUB formats are supported");
    }
  }

  private FileFormat resolveFormat(String filename) {
    String ext = FileExtensions.getExtension(filename, "");
    return "epub".equals(ext) ? FileFormat.EPUB : FileFormat.PDF;
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
