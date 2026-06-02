package com.socialapp.bookstore.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.bookstore.dto.BookReviewResponseDto;
import com.socialapp.bookstore.dto.CreateReviewRequestDto;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.entity.BookReviewEntity;
import com.socialapp.bookstore.repository.BookRepository;
import com.socialapp.bookstore.repository.BookReviewRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BookReviewService {
  private final BookReviewRepository reviewRepository;
  private final BookRepository bookRepository;
  private final BookService bookService;

  @Transactional
  public BookReviewResponseDto createOrUpdateReview(
      Integer userId, Integer bookId, CreateReviewRequestDto request) {
    bookService.findBookOrThrow(bookId);

    BookReviewEntity review =
        reviewRepository
            .findByBookIdAndUserId(bookId, userId)
            .orElseGet(() -> BookReviewEntity.builder().bookId(bookId).userId(userId).build());

    review.setRating(request.getRating());
    review.setFeedback(request.getFeedback());
    reviewRepository.save(review);

    updateBookRatingStats(bookId);
    return toDto(review);
  }

  public List<BookReviewResponseDto> getReviews(Integer bookId) {
    return reviewRepository.findByBookIdOrderByCreatedAtDesc(bookId).stream()
        .map(this::toDto)
        .toList();
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
