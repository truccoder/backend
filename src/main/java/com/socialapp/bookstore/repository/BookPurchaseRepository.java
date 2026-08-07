package com.socialapp.bookstore.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.bookstore.entity.BookPurchaseEntity;

public interface BookPurchaseRepository extends JpaRepository<BookPurchaseEntity, Integer> {

  Optional<BookPurchaseEntity> findByTransactionRef(String transactionRef);

  Optional<BookPurchaseEntity> findByBookIdAndBuyerId(Integer bookId, Integer buyerId);

  boolean existsByBookIdAndBuyerIdAndPaymentStatus(
      Integer bookId, Integer buyerId, com.socialapp.bookstore.entity.enums.PaymentStatus status);

  /**
   * Whether anyone has paid for this book. Guards deletion: {@code t_book_purchases.book_id} is ON
   * DELETE CASCADE, so removing the row would take the payment records with it.
   */
  boolean existsByBookIdAndPaymentStatus(
      Integer bookId, com.socialapp.bookstore.entity.enums.PaymentStatus status);
}
