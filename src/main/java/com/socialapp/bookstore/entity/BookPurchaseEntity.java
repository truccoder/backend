package com.socialapp.bookstore.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.socialapp.bookstore.entity.enums.PaymentStatus;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_book_purchases")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookPurchaseEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "book_purchases_seq_gen")
  @SequenceGenerator(
      name = "book_purchases_seq_gen",
      sequenceName = "q_book_purchases_id",
      allocationSize = 1)
  private Integer id;

  private Integer bookId;

  private Integer buyerId;

  private Long amount;

  @Builder.Default private String currency = "VND";

  @Enumerated(EnumType.STRING)
  @Builder.Default
  private PaymentStatus paymentStatus = PaymentStatus.PENDING;

  private String transactionRef;

  private String vnpayTransactionNo;

  private String paymentMethod;

  private OffsetDateTime paidAt;

  @CreationTimestamp private OffsetDateTime createdAt;
}
