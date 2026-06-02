package com.socialapp.bookstore.controller;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.bookstore.dto.BookResponseDto;
import com.socialapp.bookstore.dto.BookReviewResponseDto;
import com.socialapp.bookstore.dto.CreateBookRequestDto;
import com.socialapp.bookstore.dto.CreateReviewRequestDto;
import com.socialapp.bookstore.service.BookReviewService;
import com.socialapp.bookstore.service.BookService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/books")
@RequiredArgsConstructor
public class BookController {
  private final BookService bookService;
  private final BookReviewService reviewService;

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public BookResponseDto createBook(
      @RequestHeader("X-User-Id") Integer authorId,
      @RequestPart("metadata") @Valid CreateBookRequestDto request,
      @RequestPart("file") MultipartFile bookFile,
      @RequestPart(value = "cover", required = false) MultipartFile coverFile) {
    return bookService.createBook(authorId, request, bookFile, coverFile);
  }

  @GetMapping("/{bookId}")
  public BookResponseDto getBook(
      @RequestHeader("X-User-Id") Integer userId, @PathVariable Integer bookId) {
    return bookService.getBook(bookId, userId);
  }

  @GetMapping("/author/{authorId}")
  public List<BookResponseDto> getBooksByAuthor(
      @RequestHeader("X-User-Id") Integer requesterId, @PathVariable Integer authorId) {
    return bookService.getBooksByAuthor(authorId, requesterId);
  }

  @GetMapping("/{bookId}/download")
  public DownloadUrlResponse downloadBook(
      @RequestHeader("X-User-Id") Integer userId, @PathVariable Integer bookId) {
    String url = bookService.getFullDownloadUrl(bookId, userId);
    return new DownloadUrlResponse(url);
  }

  @GetMapping("/{bookId}/preview")
  public DownloadUrlResponse previewBook(@PathVariable Integer bookId) {
    String url = bookService.getPreviewUrl(bookId);
    return new DownloadUrlResponse(url);
  }

  @DeleteMapping("/{bookId}")
  public void deleteBook(
      @RequestHeader("X-User-Id") Integer authorId, @PathVariable Integer bookId) {
    bookService.deleteBook(authorId, bookId);
  }

  @PostMapping("/{bookId}/reviews")
  public BookReviewResponseDto createReview(
      @RequestHeader("X-User-Id") Integer userId,
      @PathVariable Integer bookId,
      @Valid @org.springframework.web.bind.annotation.RequestBody CreateReviewRequestDto request) {
    return reviewService.createOrUpdateReview(userId, bookId, request);
  }

  @GetMapping("/{bookId}/reviews")
  public List<BookReviewResponseDto> getReviews(@PathVariable Integer bookId) {
    return reviewService.getReviews(bookId);
  }

  record DownloadUrlResponse(String url) {}
}
