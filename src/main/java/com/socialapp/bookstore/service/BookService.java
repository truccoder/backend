package com.socialapp.bookstore.service;

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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookService {
  private final BookRepository bookRepository;
  private final BookPurchaseRepository purchaseRepository;
  private final BookStorageService bookStorageService;

  private static final List<String> ALLOWED_FORMATS = List.of("pdf", "epub");

  @Transactional
  public BookResponseDto createBook(
      Integer authorId,
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

    BookEntity book =
        BookEntity.builder()
            .authorId(authorId)
            .postId(request.getPostId())
            .title(request.getTitle())
            .description(request.getDescription())
            .fileKey(fileKey)
            .coverImageUrl(coverUrl)
            .fileFormat(format)
            .fileSizeBytes(bookFile.getSize())
            .previewPages(isFree ? 0 : request.getPreviewPages())
            .price(isFree ? 0L : request.getPrice())
            .isFree(isFree)
            .build();

    bookRepository.save(book);
    return toResponseDto(book, authorId);
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
    return bookStorageService.getPreviewUrl(book.getFileKey());
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
    String ext = getExtension(file.getOriginalFilename());
    if (!ALLOWED_FORMATS.contains(ext)) {
      throw new ValidationException("Only PDF and EPUB formats are supported");
    }
  }

  private FileFormat resolveFormat(String filename) {
    String ext = getExtension(filename);
    return "epub".equals(ext) ? FileFormat.EPUB : FileFormat.PDF;
  }

  private String getExtension(String filename) {
    if (filename == null || !filename.contains(".")) return "";
    return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
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
      previewUrl = bookStorageService.getPreviewUrl(book.getFileKey());
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
