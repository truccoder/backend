package com.socialapp.bookstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.bookstore.dto.BookReviewResponseDto;
import com.socialapp.bookstore.dto.CreateReviewRequestDto;
import com.socialapp.bookstore.dto.RatingBreakdownDto;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.entity.BookReviewEntity;
import com.socialapp.bookstore.repository.BookRepository;
import com.socialapp.bookstore.repository.BookReviewRepository;
import com.socialapp.bookstore.repository.BookReviewRepository.RatingCount;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link BookReviewService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3 BDD
 * Given/When/Then) — see {@code PostServiceTest}/{@code BookServiceTest} for the full rationale.
 *
 * <p>Unlike {@code PostService}/{@code BookService}, most of this class is straight-line
 * delegation to repositories with only two real decision points in its own bytecode: the {@code
 * isNewReview && !author.equals(reviewer)} notification guard in {@code createOrUpdateReview},
 * and the {@code name != null && !name.isBlank()} filter inside {@code actorName}. Every other
 * "branch-shaped" call ({@code Optional#orElse}, {@code Map#getOrDefault}, {@code
 * BookService#findBookOrThrow}) is a plain method call on another class, not a decision in this
 * class's own control-flow graph, so it contributes no branch here — the tests below still cover
 * both outcomes of those calls for behavioral correctness, even where JaCoCo wouldn't require it.
 */
@ExtendWith(MockitoExtension.class)
class BookReviewServiceTest {

  private static final Integer BOOK_ID = 50;
  private static final Integer AUTHOR_ID = 1;
  private static final Integer REVIEWER_ID = 2;

  @Mock private BookReviewRepository reviewRepository;
  @Mock private BookRepository bookRepository;
  @Mock private BookService bookService;
  @Mock private UserRepository userRepository;
  @Mock private NotificationService notificationService;

  @InjectMocks private BookReviewService bookReviewService;

  @Captor private ArgumentCaptor<BookReviewEntity> reviewCaptor;
  @Captor private ArgumentCaptor<BookEntity> bookCaptor;
  @Captor private ArgumentCaptor<SendNotificationRequest> notificationCaptor;

  private static BookEntity book(Integer id, Integer authorId) {
    return BookEntity.builder().id(id).authorId(authorId).title("Great Book").build();
  }

  private static CreateReviewRequestDto reviewRequest(int rating, String feedback) {
    CreateReviewRequestDto dto = new CreateReviewRequestDto();
    dto.setRating(rating);
    dto.setFeedback(feedback);
    return dto;
  }

  private static UserEntity userWithName(Integer id, String fullName) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setFullName(fullName);
    return user;
  }

  private static UserEntity userWithProfile(Integer id, String username, String fullName) {
    UserEntity user = userWithName(id, fullName);
    user.setUsername(username);
    user.setProfilePictureUrl("https://example.test/" + username + ".png");
    user.setEliteScore(42);
    return user;
  }

  private static RatingCount ratingCount(int rating, long count) {
    RatingCount rc = mock(RatingCount.class);
    when(rc.getRating()).thenReturn(rating);
    when(rc.getCount()).thenReturn(count);
    return rc;
  }

  // =====================================================================
  // createOrUpdateReview
  // =====================================================================

  @Nested
  @DisplayName("createOrUpdateReview")
  class CreateOrUpdateReviewTests {

    @Test
    @DisplayName("should create a new review and notify the author when reviewed by someone else")
    void shouldCreateNewReviewAndNotifyAuthor_whenReviewerIsNotTheAuthor() {
      // Given
      BookEntity book = book(BOOK_ID, AUTHOR_ID);
      when(bookService.findBookOrThrow(BOOK_ID)).thenReturn(book);
      when(reviewRepository.findByBookIdAndUserId(BOOK_ID, REVIEWER_ID))
          .thenReturn(Optional.empty());
      when(userRepository.findById(REVIEWER_ID))
          .thenReturn(Optional.of(userWithName(REVIEWER_ID, "Alice")));
      when(reviewRepository.getAverageRating(BOOK_ID)).thenReturn(Optional.of(4.5));
      when(reviewRepository.countByBookId(BOOK_ID)).thenReturn(3);

      // When
      BookReviewResponseDto dto =
          bookReviewService.createOrUpdateReview(
              REVIEWER_ID, BOOK_ID, reviewRequest(5, "Loved it"));

      // Then
      verify(reviewRepository).save(reviewCaptor.capture());
      BookReviewEntity savedReview = reviewCaptor.getValue();
      assertThat(savedReview.getBookId()).isEqualTo(BOOK_ID);
      assertThat(savedReview.getUserId()).isEqualTo(REVIEWER_ID);
      assertThat(savedReview.getRating()).isEqualTo(5);
      assertThat(savedReview.getFeedback()).isEqualTo("Loved it");
      assertThat(dto.getRating()).isEqualTo(5);
      assertThat(dto.getFeedback()).isEqualTo("Loved it");
      assertThat(dto.getAuthorFullName()).isEqualTo("Alice");

      verify(bookRepository).save(bookCaptor.capture());
      assertThat(bookCaptor.getValue().getAvgRating()).isEqualTo(4.5);
      assertThat(bookCaptor.getValue().getReviewCount()).isEqualTo(3);

      verify(notificationService).send(notificationCaptor.capture());
      SendNotificationRequest notification = notificationCaptor.getValue();
      assertThat(notification.getRecipientId()).isEqualTo(AUTHOR_ID);
      assertThat(notification.getActorId()).isEqualTo(REVIEWER_ID);
      assertThat(notification.getType()).isEqualTo(NotificationType.BOOK_REVIEW);
      assertThat(notification.getBody()).isEqualTo("Alice reviewed \"Great Book\"");
      assertThat(notification.getReferenceId()).isEqualTo(BOOK_ID);
      assertThat(notification.getReferenceType()).isEqualTo("BOOK");
    }

    @Test
    @DisplayName(
        "should create a new review without notifying when the author reviews their own book")
    void shouldCreateNewReviewWithoutNotifying_whenReviewerIsTheAuthor() {
      // Given
      BookEntity book = book(BOOK_ID, AUTHOR_ID);
      when(bookService.findBookOrThrow(BOOK_ID)).thenReturn(book);
      when(reviewRepository.findByBookIdAndUserId(BOOK_ID, AUTHOR_ID)).thenReturn(Optional.empty());
      when(reviewRepository.getAverageRating(BOOK_ID)).thenReturn(Optional.of(5.0));
      when(reviewRepository.countByBookId(BOOK_ID)).thenReturn(1);

      // When
      bookReviewService.createOrUpdateReview(AUTHOR_ID, BOOK_ID, reviewRequest(5, "My own book"));

      // Then
      verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName(
        "should update an existing review in place without notifying, regardless of authorship")
    void shouldUpdateExistingReviewWithoutNotifying_whenReviewAlreadyExists() {
      // Given
      BookEntity book = book(BOOK_ID, AUTHOR_ID);
      BookReviewEntity existingReview =
          BookReviewEntity.builder()
              .id(9)
              .bookId(BOOK_ID)
              .userId(REVIEWER_ID)
              .rating(3)
              .feedback("meh")
              .build();
      when(bookService.findBookOrThrow(BOOK_ID)).thenReturn(book);
      when(reviewRepository.findByBookIdAndUserId(BOOK_ID, REVIEWER_ID))
          .thenReturn(Optional.of(existingReview));
      when(reviewRepository.getAverageRating(BOOK_ID)).thenReturn(Optional.of(4.0));
      when(reviewRepository.countByBookId(BOOK_ID)).thenReturn(2);

      // When
      bookReviewService.createOrUpdateReview(REVIEWER_ID, BOOK_ID, reviewRequest(4, "better now"));

      // Then
      verify(reviewRepository).save(existingReview);
      assertThat(existingReview.getRating()).isEqualTo(4);
      assertThat(existingReview.getFeedback()).isEqualTo("better now");
      verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("should fall back to \"Someone\" when the reviewer's full name is blank")
    void shouldFallBackToSomeone_whenReviewerFullNameIsBlank() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID)).thenReturn(book(BOOK_ID, AUTHOR_ID));
      when(reviewRepository.findByBookIdAndUserId(BOOK_ID, REVIEWER_ID))
          .thenReturn(Optional.empty());
      when(userRepository.findById(REVIEWER_ID))
          .thenReturn(Optional.of(userWithName(REVIEWER_ID, "   ")));
      when(reviewRepository.getAverageRating(BOOK_ID)).thenReturn(Optional.of(3.0));
      when(reviewRepository.countByBookId(BOOK_ID)).thenReturn(1);

      // When
      bookReviewService.createOrUpdateReview(REVIEWER_ID, BOOK_ID, reviewRequest(3, "ok"));

      // Then
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getBody()).startsWith("Someone reviewed");
    }

    @Test
    @DisplayName("should fall back to \"Someone\" when the reviewer's full name is null")
    void shouldFallBackToSomeone_whenReviewerFullNameIsNull() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID)).thenReturn(book(BOOK_ID, AUTHOR_ID));
      when(reviewRepository.findByBookIdAndUserId(BOOK_ID, REVIEWER_ID))
          .thenReturn(Optional.empty());
      when(userRepository.findById(REVIEWER_ID))
          .thenReturn(Optional.of(userWithName(REVIEWER_ID, null)));
      when(reviewRepository.getAverageRating(BOOK_ID)).thenReturn(Optional.of(3.0));
      when(reviewRepository.countByBookId(BOOK_ID)).thenReturn(1);

      // When
      bookReviewService.createOrUpdateReview(REVIEWER_ID, BOOK_ID, reviewRequest(3, "ok"));

      // Then
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getBody()).startsWith("Someone reviewed");
    }

    @Test
    @DisplayName("should fall back to \"Someone\" when the reviewer's user record no longer exists")
    void shouldFallBackToSomeone_whenReviewerUserRecordIsMissing() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID)).thenReturn(book(BOOK_ID, AUTHOR_ID));
      when(reviewRepository.findByBookIdAndUserId(BOOK_ID, REVIEWER_ID))
          .thenReturn(Optional.empty());
      when(userRepository.findById(REVIEWER_ID)).thenReturn(Optional.empty());
      when(reviewRepository.getAverageRating(BOOK_ID)).thenReturn(Optional.of(3.0));
      when(reviewRepository.countByBookId(BOOK_ID)).thenReturn(1);

      // When
      bookReviewService.createOrUpdateReview(REVIEWER_ID, BOOK_ID, reviewRequest(3, "ok"));

      // Then
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getBody()).startsWith("Someone reviewed");
    }

    @Test
    @DisplayName("should round the recomputed average rating to one decimal place")
    void shouldRoundAverageRating_whenUpdatingBookStats() {
      // Given
      BookEntity book = book(BOOK_ID, REVIEWER_ID);
      when(bookService.findBookOrThrow(BOOK_ID)).thenReturn(book);
      when(reviewRepository.findByBookIdAndUserId(BOOK_ID, REVIEWER_ID))
          .thenReturn(Optional.empty());
      when(reviewRepository.getAverageRating(BOOK_ID)).thenReturn(Optional.of(4.26));
      when(reviewRepository.countByBookId(BOOK_ID)).thenReturn(5);

      // When
      bookReviewService.createOrUpdateReview(REVIEWER_ID, BOOK_ID, reviewRequest(4, "great"));

      // Then
      verify(bookRepository).save(bookCaptor.capture());
      assertThat(bookCaptor.getValue().getAvgRating()).isEqualTo(4.3);
    }

    @Test
    @DisplayName("should default the average rating to zero when the book has no ratings yet")
    void shouldDefaultAverageRatingToZero_whenNoRatingsExist() {
      // Given
      BookEntity book = book(BOOK_ID, REVIEWER_ID);
      when(bookService.findBookOrThrow(BOOK_ID)).thenReturn(book);
      when(reviewRepository.findByBookIdAndUserId(BOOK_ID, REVIEWER_ID))
          .thenReturn(Optional.empty());
      when(reviewRepository.getAverageRating(BOOK_ID)).thenReturn(Optional.empty());
      when(reviewRepository.countByBookId(BOOK_ID)).thenReturn(0);

      // When
      bookReviewService.createOrUpdateReview(REVIEWER_ID, BOOK_ID, reviewRequest(1, "first"));

      // Then
      verify(bookRepository).save(bookCaptor.capture());
      assertThat(bookCaptor.getValue().getAvgRating()).isZero();
      assertThat(bookCaptor.getValue().getReviewCount()).isZero();
    }
  }

  // =====================================================================
  // getReviews
  // =====================================================================

  @Nested
  @DisplayName("getReviews")
  class GetReviewsTests {

    @Test
    @DisplayName("should reject when the book does not exist")
    void shouldThrowNotFoundException_whenBookDoesNotExist() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenThrow(new NotFoundException("Book not found: " + BOOK_ID));

      // When / Then
      assertThatThrownBy(() -> bookReviewService.getReviews(BOOK_ID))
          .isInstanceOf(NotFoundException.class);
      verify(reviewRepository, never()).findByBookIdOrderByCreatedAtDesc(any());
    }

    @Test
    @DisplayName("should return every review for the book mapped to a response DTO")
    void shouldReturnMappedReviews_whenBookExists() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID)).thenReturn(book(BOOK_ID, AUTHOR_ID));
      BookReviewEntity review1 =
          BookReviewEntity.builder().id(1).userId(REVIEWER_ID).rating(5).feedback("Great").build();
      BookReviewEntity review2 =
          BookReviewEntity.builder().id(2).userId(AUTHOR_ID).rating(3).feedback("Meh").build();
      when(reviewRepository.findByBookIdOrderByCreatedAtDesc(BOOK_ID))
          .thenReturn(List.of(review1, review2));

      // When
      List<BookReviewResponseDto> result = bookReviewService.getReviews(BOOK_ID);

      // Then
      assertThat(result).hasSize(2);
      assertThat(result).extracting(BookReviewResponseDto::getRating).containsExactly(5, 3);
    }

    @Test
    @DisplayName("should attach the reviewer's byline via a single batched author lookup")
    void shouldAttachAuthorByline_batchedAcrossAllReviewsOnThePage() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID)).thenReturn(book(BOOK_ID, AUTHOR_ID));
      BookReviewEntity review1 =
          BookReviewEntity.builder().id(1).userId(REVIEWER_ID).rating(5).feedback("Great").build();
      BookReviewEntity review2 =
          BookReviewEntity.builder().id(2).userId(AUTHOR_ID).rating(3).feedback("Meh").build();
      when(reviewRepository.findByBookIdOrderByCreatedAtDesc(BOOK_ID))
          .thenReturn(List.of(review1, review2));
      when(userRepository.findAllById(Set.of(REVIEWER_ID, AUTHOR_ID)))
          .thenReturn(List.of(userWithProfile(REVIEWER_ID, "alice", "Alice")));

      // When
      List<BookReviewResponseDto> result = bookReviewService.getReviews(BOOK_ID);

      // Then
      verify(userRepository, never()).findById(any());
      BookReviewResponseDto reviewerDto =
          result.stream()
              .filter(dto -> dto.getUserId().equals(REVIEWER_ID))
              .findFirst()
              .orElseThrow();
      assertThat(reviewerDto.getAuthorUsername()).isEqualTo("alice");
      assertThat(reviewerDto.getAuthorFullName()).isEqualTo("Alice");
      assertThat(reviewerDto.getAuthorEliteScore()).isEqualTo(42);

      BookReviewResponseDto orphanedDto =
          result.stream()
              .filter(dto -> dto.getUserId().equals(AUTHOR_ID))
              .findFirst()
              .orElseThrow();
      assertThat(orphanedDto.getAuthorUsername()).isNull();
      assertThat(orphanedDto.getAuthorFullName()).isNull();
    }

    @Test
    @DisplayName("should return an empty list when the book has no reviews")
    void shouldReturnEmptyList_whenBookHasNoReviews() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID)).thenReturn(book(BOOK_ID, AUTHOR_ID));
      when(reviewRepository.findByBookIdOrderByCreatedAtDesc(BOOK_ID)).thenReturn(List.of());

      // When
      List<BookReviewResponseDto> result = bookReviewService.getReviews(BOOK_ID);

      // Then
      assertThat(result).isEmpty();
    }
  }

  // =====================================================================
  // getRatingBreakdown
  // =====================================================================

  @Nested
  @DisplayName("getRatingBreakdown")
  class GetRatingBreakdownTests {

    @Test
    @DisplayName("should reject when the book does not exist")
    void shouldThrowNotFoundException_whenBookDoesNotExist() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenThrow(new NotFoundException("Book not found: " + BOOK_ID));

      // When / Then
      assertThatThrownBy(() -> bookReviewService.getRatingBreakdown(BOOK_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should tally star counts and the total when ratings exist")
    void shouldComputeBreakdown_whenRatingsExist() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID)).thenReturn(book(BOOK_ID, AUTHOR_ID));
      List<RatingCount> counts = List.of(ratingCount(5, 3), ratingCount(1, 1));
      when(reviewRepository.countGroupedByRating(BOOK_ID)).thenReturn(counts);

      // When
      RatingBreakdownDto breakdown = bookReviewService.getRatingBreakdown(BOOK_ID);

      // Then
      assertThat(breakdown.oneStarCount()).isEqualTo(1);
      assertThat(breakdown.twoStarsCount()).isZero();
      assertThat(breakdown.threeStarsCount()).isZero();
      assertThat(breakdown.fourStarsCount()).isZero();
      assertThat(breakdown.fiveStarsCount()).isEqualTo(3);
      assertThat(breakdown.totalRatings()).isEqualTo(4);
    }

    @Test
    @DisplayName("should return all zeros when the book has no ratings")
    void shouldComputeBreakdown_whenNoRatingsExist() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID)).thenReturn(book(BOOK_ID, AUTHOR_ID));
      when(reviewRepository.countGroupedByRating(BOOK_ID)).thenReturn(List.of());

      // When
      RatingBreakdownDto breakdown = bookReviewService.getRatingBreakdown(BOOK_ID);

      // Then
      assertThat(breakdown.totalRatings()).isZero();
      assertThat(breakdown.oneStarCount()).isZero();
      assertThat(breakdown.fiveStarsCount()).isZero();
    }
  }
}
