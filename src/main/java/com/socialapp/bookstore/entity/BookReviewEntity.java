package com.socialapp.bookstore.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_book_reviews")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookReviewEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "book_reviews_seq_gen")
  @SequenceGenerator(
      name = "book_reviews_seq_gen",
      sequenceName = "q_book_reviews_id",
      allocationSize = 1)
  private Integer id;

  private Integer bookId;

  private Integer userId;

  private Integer rating;

  @Column(columnDefinition = "TEXT")
  private String feedback;

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;
}
