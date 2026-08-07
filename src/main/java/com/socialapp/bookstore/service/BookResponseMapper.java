package com.socialapp.bookstore.service;

import org.springframework.stereotype.Component;

import com.socialapp.bookstore.dto.BookResponseDto;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.entity.enums.PaymentStatus;
import com.socialapp.bookstore.repository.BookPurchaseRepository;

import lombok.RequiredArgsConstructor;

/**
 * Renders a book for one particular reader.
 *
 * <p>This is a policy, not plumbing: it decides whether the caller gets a download URL or only a
 * preview, which is the entire commercial boundary of the store. It sat as a private method inside
 * {@link BookService} next to create and delete, where the rule was easy to miss and easy to
 * duplicate — every read path ({@code getBook}, {@code getLibraryPage}, {@code getBooksByAuthor})
 * has to go through it, and a fourth read path that built a DTO by hand would silently hand out
 * paid files.
 */
@Component
@RequiredArgsConstructor
public class BookResponseMapper {

  private final BookPurchaseRepository purchaseRepository;
  private final BookStorageService bookStorageService;

  /**
   * @param requesterId may be null — a signed-out visitor. Null is treated as "has not bought it",
   *     which is what a guest should see: cover and blurb, preview instead of the file.
   */
  public BookResponseDto toResponseDto(BookEntity book, Integer requesterId) {
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

  /**
   * Which object to serve as the preview.
   *
   * <p>Falls back to the full file when no separate preview was generated. That fallback is safe
   * only because the preview URL is served through {@code BookStorageService.getPreviewUrl}, which
   * caps what it hands out; pointing a raw download URL at this key would give the whole book away.
   */
  public String previewKeyOrFallback(BookEntity book) {
    return book.getPreviewFileKey() != null ? book.getPreviewFileKey() : book.getFileKey();
  }
}
