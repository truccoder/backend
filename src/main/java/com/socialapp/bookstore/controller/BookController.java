package com.socialapp.bookstore.controller;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import com.socialapp.bookstore.dto.*;
import com.socialapp.bookstore.service.BookReviewService;
import com.socialapp.bookstore.service.BookService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/books")
@RequiredArgsConstructor
public class BookController {
  private final BookService bookService;
  private final BookReviewService reviewService;

  @GetMapping("/{bookId}")
  public BookResponseDto getBook(@PathVariable Integer bookId) {
    return bookService.getBook(bookId, SecurityUtils.getCurrentUserId());
  }

  /**
   * The books written by one author — the "sách đã viết" section of a public profile, and
   * therefore readable by a guest.
   *
   * <p>{@code getCurrentUserIdOrNull()} because of that: the throwing variant made this endpoint
   * 401 for an anonymous caller no matter what {@code SecurityConfig} allowed. {@code
   * BookService.toResponseDto} already treats a null requester as "has not bought it" — such a
   * reader gets the preview URL and no download URL, which is exactly what a guest should see.
   */
  @GetMapping("/author/{authorId}")
  public List<BookResponseDto> getBooksByAuthor(@PathVariable Integer authorId) {
    return bookService.getBooksByAuthor(authorId, SecurityUtils.getCurrentUserIdOrNull());
  }

  @GetMapping("/{bookId}/download")
  public DownloadUrlResponse downloadBook(@PathVariable Integer bookId) {
    String url = bookService.getFullDownloadUrl(bookId, SecurityUtils.getCurrentUserId());
    return new DownloadUrlResponse(url);
  }

  @GetMapping("/{bookId}/preview")
  public DownloadUrlResponse previewBook(@PathVariable Integer bookId) {
    String url = bookService.getPreviewUrl(bookId);
    return new DownloadUrlResponse(url);
  }

  @DeleteMapping("/{bookId}")
  public void deleteBook(@PathVariable Integer bookId) {
    bookService.deleteBook(SecurityUtils.getCurrentUserId(), bookId);
  }

  @PostMapping("/{bookId}/reviews")
  public BookReviewResponseDto createReview(
      @PathVariable Integer bookId, @Valid @RequestBody CreateReviewRequestDto request) {
    return reviewService.createOrUpdateReview(SecurityUtils.getCurrentUserId(), bookId, request);
  }

  @GetMapping("/{bookId}/reviews")
  public List<BookReviewResponseDto> getReviews(@PathVariable Integer bookId) {
    return reviewService.getReviews(bookId);
  }

  @GetMapping("/{bookId}/reviews/breakdown")
  public RatingBreakdownDto getRatingBreakdown(@PathVariable Integer bookId) {
    return reviewService.getRatingBreakdown(bookId);
  }
}
