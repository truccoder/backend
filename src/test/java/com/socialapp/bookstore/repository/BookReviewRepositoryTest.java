package com.socialapp.bookstore.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.entity.BookReviewEntity;
import com.socialapp.bookstore.entity.enums.FileFormat;
import com.socialapp.bookstore.repository.BookReviewRepository.RatingCount;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link BookReviewRepository} against a real PostgreSQL
 * instance (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own
 * transaction, so no manual cleanup is needed. {@code t_book_reviews} has foreign keys to a real
 * book and a real user, so each test seeds a book first.
 */
@Transactional
class BookReviewRepositoryTest extends AbstractIntegrationTest {

  @Autowired private BookReviewRepository bookReviewRepository;
  @Autowired private BookRepository bookRepository;
  @Autowired private UserRepository userRepository;

  private Integer bookId;

  @BeforeEach
  void seedBook() {
    Integer authorId = userRepository.saveAndFlush(user("author@example.com", "author")).getId();
    bookId = bookRepository.saveAndFlush(book(authorId)).getId();
  }

  private static UserEntity user(String email, String username) {
    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPassword("hashed-password");
    user.setUsername(username);
    user.setFullName("Test User");
    return user;
  }

  private static BookEntity book(Integer authorId) {
    return BookEntity.builder()
        .authorId(authorId)
        .title("Test Book")
        .fileKey("books/test")
        .fileFormat(FileFormat.PDF)
        .build();
  }

  private static BookReviewEntity review(Integer bookId, Integer userId, int rating) {
    return BookReviewEntity.builder().bookId(bookId).userId(userId).rating(rating).build();
  }

  private Integer newUserId(String email, String username) {
    return userRepository.saveAndFlush(user(email, username)).getId();
  }

  @Nested
  @DisplayName("findByBookIdAndUserId")
  class FindByBookIdAndUserId {

    @Test
    @DisplayName("finds the user's review for the book")
    void findsExistingReview() {
      // Given
      Integer userId = newUserId("reviewer@example.com", "reviewer");
      bookReviewRepository.saveAndFlush(review(bookId, userId, 5));

      // When
      Optional<BookReviewEntity> result =
          bookReviewRepository.findByBookIdAndUserId(bookId, userId);

      // Then
      assertThat(result).isPresent();
      assertThat(result.get().getRating()).isEqualTo(5);
    }

    @Test
    @DisplayName("returns empty when the user has not reviewed the book")
    void returnsEmptyWhenNoReview() {
      // Given
      Integer userId = newUserId("reviewer@example.com", "reviewer");

      // When
      Optional<BookReviewEntity> result =
          bookReviewRepository.findByBookIdAndUserId(bookId, userId);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("findByBookIdOrderByCreatedAtDesc")
  class FindByBookIdOrderByCreatedAtDesc {

    @Test
    @DisplayName("orders the book's reviews newest first")
    void ordersNewestFirst() {
      // Given
      BookReviewEntity first =
          bookReviewRepository.saveAndFlush(review(bookId, newUserId("a@example.com", "a"), 4));
      BookReviewEntity second =
          bookReviewRepository.saveAndFlush(review(bookId, newUserId("b@example.com", "b"), 3));

      // When
      List<BookReviewEntity> result = bookReviewRepository.findByBookIdOrderByCreatedAtDesc(bookId);

      // Then
      assertThat(result)
          .extracting(BookReviewEntity::getId)
          .containsExactly(second.getId(), first.getId());
    }

    @Test
    @DisplayName("returns an empty list when the book has no reviews")
    void returnsEmptyListWhenNoReviews() {
      // When
      List<BookReviewEntity> result = bookReviewRepository.findByBookIdOrderByCreatedAtDesc(bookId);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("getAverageRating")
  class GetAverageRating {

    @Test
    @DisplayName("computes the average of all ratings for the book")
    void computesAverage() {
      // Given
      bookReviewRepository.saveAndFlush(review(bookId, newUserId("a@example.com", "a"), 4));
      bookReviewRepository.saveAndFlush(review(bookId, newUserId("b@example.com", "b"), 2));

      // When
      Optional<Double> result = bookReviewRepository.getAverageRating(bookId);

      // Then
      assertThat(result).isPresent();
      assertThat(result.get()).isCloseTo(3.0, within(0.001));
    }

    @Test
    @DisplayName("returns empty when the book has no reviews")
    void returnsEmptyWhenNoReviews() {
      // When
      Optional<Double> result = bookReviewRepository.getAverageRating(bookId);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("countByBookId")
  class CountByBookId {

    @Test
    @DisplayName("counts the number of reviews for the book")
    void countsReviews() {
      // Given
      bookReviewRepository.saveAndFlush(review(bookId, newUserId("a@example.com", "a"), 5));
      bookReviewRepository.saveAndFlush(review(bookId, newUserId("b@example.com", "b"), 4));

      // When
      int result = bookReviewRepository.countByBookId(bookId);

      // Then
      assertThat(result).isEqualTo(2);
    }

    @Test
    @DisplayName("returns zero when the book has no reviews")
    void returnsZeroWhenNoReviews() {
      // When
      int result = bookReviewRepository.countByBookId(bookId);

      // Then
      assertThat(result).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("countGroupedByRating")
  class CountGroupedByRating {

    @Test
    @DisplayName("groups review counts by rating value")
    void groupsCountsByRating() {
      // Given
      bookReviewRepository.saveAndFlush(review(bookId, newUserId("a@example.com", "a"), 5));
      bookReviewRepository.saveAndFlush(review(bookId, newUserId("b@example.com", "b"), 5));
      bookReviewRepository.saveAndFlush(review(bookId, newUserId("c@example.com", "c"), 3));

      // When
      List<RatingCount> result = bookReviewRepository.countGroupedByRating(bookId);

      // Then
      assertThat(result)
          .extracting(RatingCount::getRating, RatingCount::getCount)
          .containsExactlyInAnyOrder(tuple(5, 2L), tuple(3, 1L));
    }

    @Test
    @DisplayName("returns an empty list when the book has no reviews")
    void returnsEmptyListWhenNoReviews() {
      // When
      List<RatingCount> result = bookReviewRepository.countGroupedByRating(bookId);

      // Then
      assertThat(result).isEmpty();
    }
  }
}
