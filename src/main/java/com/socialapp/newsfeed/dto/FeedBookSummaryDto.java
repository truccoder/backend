package com.socialapp.newsfeed.dto;

import com.socialapp.bookstore.entity.enums.FileFormat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Book summary shown inline in the newsfeed. Deliberately excludes {@code downloadUrl}/{@code
 * previewUrl}: those are presigned MinIO URLs that expire in 24h (far shorter than this DTO's
 * 7-day feed cache TTL) and depend on the viewing user's purchase status, which isn't known at
 * fan-out time. Frontend must call {@code GET /v1/api/books/{bookId}/preview} or {@code
 * /download} using {@code bookId} to get a fresh, viewer-scoped URL right before rendering.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedBookSummaryDto {
  private Integer bookId;
  private String title;
  private String description;
  private String coverImageUrl;
  private FileFormat fileFormat;
  private Long fileSizeBytes;
  private Integer totalPages;
  private Integer previewPages;
  private Long price;
  private String currency;
  private Boolean isFree;
  private Double avgRating;
  private Integer reviewCount;
  private long oneStarCount;
  private long twoStarsCount;
  private long threeStarsCount;
  private long fourStarsCount;
  private long fiveStarsCount;
  private long totalRatings;
}
