package com.socialapp.bookstore.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.socialapp.bookstore.dto.*;
import com.socialapp.bookstore.service.BookReviewService;
import com.socialapp.bookstore.service.BookService;
import com.socialapp.common.enums.LearningCategory;
import com.socialapp.common.utils.Constants;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@Validated
@RestController
@RequestMapping("/v1/api/books")
@RequiredArgsConstructor
public class BookController {
  private final BookService bookService;
  private final BookReviewService reviewService;

  /**
   * The Library front page: every book, newest first, cursor-paginated.
   *
   * <p>Signed-in only. {@code /books/author/{id}} is open to guests because it is a section of a
   * public profile; the whole catalogue is not part of any profile, and the guest-readable surface
   * in {@code SecurityConfig} is a list of specific pages rather than a rule about GETs. Opening
   * it later means adding the path there and to {@code GuestRateLimitProperties.paths} together.
   *
   * <p>{@code limit} is capped at 50: this endpoint signs a storage URL per row, so an uncapped
   * limit is a request that makes the server do unbounded crypto work.
   *
   * <p>{@code category} vắng mặt nghĩa là toàn bộ Thư viện — tab "Tất cả" của FE không gửi tham
   * số này chứ không gửi chuỗi rỗng. Một giá trị không thuộc {@code LearningCategory} thì Spring
   * từ chối bind và trả 400; đó là hành vi mong muốn, vì tab gõ sai tên mà im lặng trả về toàn bộ
   * danh sách sẽ trông y hệt như một bộ lọc hỏng.
   */
  @GetMapping
  public BookPageResponseDto getLibrary(
      @RequestParam(required = false) Integer cursor,
      @RequestParam(required = false) LearningCategory category,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE_SIZE)
          @Positive
          @Max(Constants.MAX_PAGINATION_PAGE_SIZE)
          int limit) {
    return bookService.getLibraryPage(cursor, limit, category, SecurityUtils.getCurrentUserId());
  }

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
