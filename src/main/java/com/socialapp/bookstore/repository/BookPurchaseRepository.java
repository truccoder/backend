package com.socialapp.bookstore.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.bookstore.entity.BookPurchaseEntity;

public interface BookPurchaseRepository extends JpaRepository<BookPurchaseEntity, Integer> {

  Optional<BookPurchaseEntity> findByTransactionRef(String transactionRef);

  Optional<BookPurchaseEntity> findByBookIdAndBuyerId(Integer bookId, Integer buyerId);

  boolean existsByBookIdAndBuyerIdAndPaymentStatus(
      Integer bookId, Integer buyerId, com.socialapp.bookstore.entity.enums.PaymentStatus status);
}
