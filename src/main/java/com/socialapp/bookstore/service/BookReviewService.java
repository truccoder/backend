package com.socialapp.bookstore.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
import com.socialapp.notifications.NotificationMessages;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.reputation.RepLevel;
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

    // Looked up unconditionally, not just when a notification fires: the response DTO carries the
    // reviewer's own byline now (B45 — every other list-of-authored-things DTO already does,
    // BookReviewResponseDto was the last holdout), and a review always has a reviewer to name.
    UserEntity reviewer = userRepository.findById(userId).orElse(null);

    if (isNewReview && !book.getAuthorId().equals(userId)) {
      String actor = displayName(reviewer);
      notificationService.send(
          SendNotificationRequest.builder()
              .recipientId(book.getAuthorId())
              .actorId(userId)
              .type(NotificationType.BOOK_REVIEW)
              .title("New review on your book")
              .body(actor + " reviewed \"" + book.getTitle() + "\"")
              .messageKey(NotificationMessages.BOOK_REVIEW)
              .messageArgs(NotificationMessages.args("actor", actor, "book", book.getTitle()))
              .referenceId(bookId)
              .referenceType("BOOK")
              .build());
    }

    return toDto(review, reviewer);
  }

  private static String displayName(UserEntity user) {
    return user == null || user.getFullName() == null || user.getFullName().isBlank()
        ? "Someone"
        : user.getFullName();
  }

  /**
   * Every review for the book, each carrying the reviewer's byline — same shape as {@code
   * CommentResponseDto}'s author block, and the same reason: {@code BookReviewList} on the client
   * used to walk {@code useReputation}/{@code usePublicProfile} per row to get here, two requests
   * per reviewer on a page that can hold many.
   *
   * <p>One batch query for every author on the page ({@code findAllById}), not one per review —
   * same trick as {@code CommentService#hydrate}.
   */
  public List<BookReviewResponseDto> getReviews(Integer bookId) {
    // Was previously missing: download/preview both 404 for a nonexistent book via
    // findBookOrThrow, but this endpoint silently returned an empty list instead.
    bookService.findBookOrThrow(bookId);
    List<BookReviewEntity> reviews = reviewRepository.findByBookIdOrderByCreatedAtDesc(bookId);

    Set<Integer> authorIds =
        reviews.stream().map(BookReviewEntity::getUserId).collect(Collectors.toSet());
    Map<Integer, UserEntity> authorsById =
        userRepository.findAllById(authorIds).stream()
            .collect(Collectors.toMap(UserEntity::getId, Function.identity()));

    return reviews.stream()
        .map(review -> toDto(review, authorsById.get(review.getUserId())))
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

  private BookReviewResponseDto toDto(BookReviewEntity entity, UserEntity author) {
    BookReviewResponseDto.BookReviewResponseDtoBuilder builder =
        BookReviewResponseDto.builder()
            .id(entity.getId())
            .userId(entity.getUserId())
            .rating(entity.getRating())
            .feedback(entity.getFeedback())
            .createdAt(entity.getCreatedAt());
    return withAuthor(builder, author).build();
  }

  /**
   * Fills in every field derived from the reviewer's row, or none of them — same one-decision
   * shape as {@code CommentService#withAuthor}. A missing row (deleted account) is not an error: a
   * review outlives the account that wrote it, same as a comment.
   */
  private static BookReviewResponseDto.BookReviewResponseDtoBuilder withAuthor(
      BookReviewResponseDto.BookReviewResponseDtoBuilder builder, UserEntity author) {
    if (author == null) {
      return builder;
    }
    return builder
        .authorUsername(author.getUsername())
        .authorFullName(author.getFullName())
        .authorProfilePictureUrl(author.getProfilePictureUrl())
        .authorEliteScore(author.getEliteScore())
        .authorLevelName(RepLevel.displayNameForScore(author.getEliteScore()));
  }
}
