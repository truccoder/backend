package com.socialapp.bookstore.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.socialapp.bookstore.entity.BookReviewEntity;

public interface BookReviewRepository extends JpaRepository<BookReviewEntity, Integer> {

  Optional<BookReviewEntity> findByBookIdAndUserId(Integer bookId, Integer userId);

  List<BookReviewEntity> findByBookIdOrderByCreatedAtDesc(Integer bookId);

  @Query("SELECT AVG(r.rating) FROM BookReviewEntity r WHERE r.bookId = :bookId")
  Optional<Double> getAverageRating(Integer bookId);

  int countByBookId(Integer bookId);
}
