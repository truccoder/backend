package com.socialapp.bookstore.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.bookstore.dto.BookReviewResponseDto;
import com.socialapp.bookstore.dto.CreateReviewRequestDto;
import com.socialapp.bookstore.dto.RatingBreakdownDto;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.entity.BookReviewEntity;
import com.socialapp.bookstore.repository.BookRepository;
import com.socialapp.bookstore.repository.BookReviewRepository;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BookReviewService {
  private final BookReviewRepository reviewRepository;
  private final BookRepository bookRepository;
  private final BookService bookService;
  private final UserRepository userRepository;
  private final NotificationService notificationService;

  @Transactional
  public BookReviewResponseDto createOrUpdateReview(
      Integer userId, Integer bookId, CreateReviewRequestDto request) {
    BookEntity book = bookService.findBookOrThrow(bookId);

    boolean isNewReview = reviewRepository.findByBookIdAndUserId(bookId, userId).isEmpty();
    BookReviewEntity review =
        reviewRepository
            .findByBookIdAndUserId(bookId, userId)
            .orElseGet(() -> BookReviewEntity.builder().bookId(bookId).userId(userId).build());

    review.setRating(request.getRating());
    review.setFeedback(request.getFeedback());
    reviewRepository.save(review);

    updateBookRatingStats(bookId);

    if (isNewReview && !book.getAuthorId().equals(userId)) {
      notificationService.send(
          SendNotificationRequest.builder()
              .recipientId(book.getAuthorId())
              .actorId(userId)
              .type(NotificationType.BOOK_REVIEW)
              .title("New review on your book")
              .body(actorName(userId) + " reviewed \"" + book.getTitle() + "\"")
              .referenceId(bookId)
              .referenceType("BOOK")
              .build());
    }

    return toDto(review);
  }

  private String actorName(Integer userId) {
    return userRepository
        .findById(userId)
        .map(UserEntity::getFullName)
        .filter(name -> !name.isBlank())
        .orElse("Someone");
  }

  public List<BookReviewResponseDto> getReviews(Integer bookId) {
    // Was previously missing: download/preview both 404 for a nonexistent book via
    // findBookOrThrow, but this endpoint silently returned an empty list instead.
    bookService.findBookOrThrow(bookId);
    return reviewRepository.findByBookIdOrderByCreatedAtDesc(bookId).stream()
        .map(this::toDto)
        .toList();
  }

  public RatingBreakdownDto getRatingBreakdown(Integer bookId) {
    bookService.findBookOrThrow(bookId);
    Map<Integer, Long> countsByRating =
        reviewRepository.countGroupedByRating(bookId).stream()
            .collect(
                Collectors.toMap(
                    BookReviewRepository.RatingCount::getRating,
                    BookReviewRepository.RatingCount::getCount));

    long oneStar = countsByRating.getOrDefault(1, 0L);
    long twoStars = countsByRating.getOrDefault(2, 0L);
    long threeStars = countsByRating.getOrDefault(3, 0L);
    long fourStars = countsByRating.getOrDefault(4, 0L);
    long fiveStars = countsByRating.getOrDefault(5, 0L);

    return new RatingBreakdownDto(
        oneStar,
        twoStars,
        threeStars,
        fourStars,
        fiveStars,
        oneStar + twoStars + threeStars + fourStars + fiveStars);
  }

  private void updateBookRatingStats(Integer bookId) {
    BookEntity book = bookService.findBookOrThrow(bookId);
    Double avg = reviewRepository.getAverageRating(bookId).orElse(0.0);
    int count = reviewRepository.countByBookId(bookId);
    book.setAvgRating(Math.round(avg * 10.0) / 10.0);
    book.setReviewCount(count);
    bookRepository.save(book);
  }

  private BookReviewResponseDto toDto(BookReviewEntity entity) {
    return BookReviewResponseDto.builder()
        .id(entity.getId())
        .userId(entity.getUserId())
        .rating(entity.getRating())
        .feedback(entity.getFeedback())
        .createdAt(entity.getCreatedAt())
        .build();
  }
}
