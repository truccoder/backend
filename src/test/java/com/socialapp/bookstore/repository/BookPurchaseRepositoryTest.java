package com.socialapp.bookstore.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.entity.BookPurchaseEntity;
import com.socialapp.bookstore.entity.enums.FileFormat;
import com.socialapp.bookstore.entity.enums.PaymentStatus;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link BookPurchaseRepository} against a real PostgreSQL
 * instance (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own
 * transaction, so no manual cleanup is needed. {@code t_book_purchases} has foreign keys to a
 * real book and a real buyer, so each test seeds those first.
 */
@Transactional
class BookPurchaseRepositoryTest extends AbstractIntegrationTest {

  @Autowired private BookPurchaseRepository bookPurchaseRepository;
  @Autowired private BookRepository bookRepository;
  @Autowired private UserRepository userRepository;

  private Integer bookId;
  private Integer buyerId;

  @BeforeEach
  void seedBookAndBuyer() {
    Integer authorId = userRepository.saveAndFlush(user("author@example.com", "author")).getId();
    bookId = bookRepository.saveAndFlush(book(authorId)).getId();
    buyerId = userRepository.saveAndFlush(user("buyer@example.com", "buyer")).getId();
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

  private static BookPurchaseEntity purchase(
      Integer bookId, Integer buyerId, String transactionRef, PaymentStatus status) {
    return BookPurchaseEntity.builder()
        .bookId(bookId)
        .buyerId(buyerId)
        .amount(10000L)
        .transactionRef(transactionRef)
        .paymentStatus(status)
        .build();
  }

  @Nested
  @DisplayName("findByTransactionRef")
  class FindByTransactionRef {

    @Test
    @DisplayName("finds a purchase by its exact transaction reference")
    void findsPurchaseByTransactionRef() {
      // Given
      bookPurchaseRepository.saveAndFlush(
          purchase(bookId, buyerId, "txn-abc", PaymentStatus.COMPLETED));

      // When
      Optional<BookPurchaseEntity> result = bookPurchaseRepository.findByTransactionRef("txn-abc");

      // Then
      assertThat(result).isPresent();
    }

    @Test
    @DisplayName("returns empty when no purchase matches the reference")
    void returnsEmptyWhenRefNotFound() {
      // When
      Optional<BookPurchaseEntity> result =
          bookPurchaseRepository.findByTransactionRef("nonexistent-ref");

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("findByBookIdAndBuyerId")
  class FindByBookIdAndBuyerId {

    @Test
    @DisplayName("finds the purchase for the given book and buyer")
    void findsExistingPurchase() {
      // Given
      bookPurchaseRepository.saveAndFlush(
          purchase(bookId, buyerId, "txn-1", PaymentStatus.COMPLETED));

      // When
      Optional<BookPurchaseEntity> result =
          bookPurchaseRepository.findByBookIdAndBuyerId(bookId, buyerId);

      // Then
      assertThat(result).isPresent();
    }

    @Test
    @DisplayName("returns empty when the buyer has not purchased the book")
    void returnsEmptyWhenNoPurchase() {
      // When
      Optional<BookPurchaseEntity> result =
          bookPurchaseRepository.findByBookIdAndBuyerId(bookId, buyerId);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("existsByBookIdAndBuyerIdAndPaymentStatus")
  class ExistsByBookIdAndBuyerIdAndPaymentStatus {

    @Test
    @DisplayName("returns true when a purchase with the given status exists")
    void returnsTrueWhenMatchingPurchaseExists() {
      // Given
      bookPurchaseRepository.saveAndFlush(
          purchase(bookId, buyerId, "txn-1", PaymentStatus.COMPLETED));

      // When
      boolean result =
          bookPurchaseRepository.existsByBookIdAndBuyerIdAndPaymentStatus(
              bookId, buyerId, PaymentStatus.COMPLETED);

      // Then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("returns false when the purchase has a different payment status")
    void returnsFalseWhenStatusDiffers() {
      // Given
      bookPurchaseRepository.saveAndFlush(
          purchase(bookId, buyerId, "txn-1", PaymentStatus.PENDING));

      // When
      boolean result =
          bookPurchaseRepository.existsByBookIdAndBuyerIdAndPaymentStatus(
              bookId, buyerId, PaymentStatus.COMPLETED);

      // Then
      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("returns false when there is no purchase at all")
    void returnsFalseWhenNoPurchase() {
      // When
      boolean result =
          bookPurchaseRepository.existsByBookIdAndBuyerIdAndPaymentStatus(
              bookId, buyerId, PaymentStatus.COMPLETED);

      // Then
      assertThat(result).isFalse();
    }
  }
}
