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

  private record GeneratedPreview(String fileKey, int totalUnits) {}

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
    validateFile(bookFile);

    String fileKey = bookStorageService.uploadBook(authorId, bookFile);
    String coverUrl = null;
    if (coverFile != null && !coverFile.isEmpty()) {
      coverUrl = bookStorageService.uploadCover(authorId, coverFile);
    }

    FileFormat format = resolveFormat(bookFile.getOriginalFilename());
    boolean isFree = request.getPrice() == null || request.getPrice() <= 0;

    if (!isFree && (request.getPreviewPages() == null || request.getPreviewPages() <= 0)) {
      throw new ValidationException("Paid books must have preview pages configured");
    }

    String previewFileKey = null;
    Integer totalPages;

    if (isFree) {
      totalPages = countPages(format, bookFile);
    } else {
      GeneratedPreview preview =
          generatePreview(authorId, format, bookFile, request.getPreviewPages());
      previewFileKey = preview.fileKey();
      totalPages = preview.totalUnits();
    }

    BookEntity book =
        BookEntity.builder()
            .authorId(authorId)
            .postId(postId)
            .title(request.getTitle())
            .description(request.getDescription())
            .fileKey(fileKey)
            .previewFileKey(previewFileKey)
            .coverImageUrl(coverUrl)
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
      Integer authorId, FileFormat format, MultipartFile bookFile, int previewPages) {
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

      String extension = format == FileFormat.EPUB ? "epub" : "pdf";
      String previewFileKey =
          bookStorageService.uploadPreview(authorId, result.previewBytes(), extension);
      return new GeneratedPreview(previewFileKey, result.totalUnits());
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
    bookRepository.delete(book);
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
        .coverImageUrl(book.getCoverImageUrl())
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
