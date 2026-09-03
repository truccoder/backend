package com.socialapp.bookstore.dto;

import java.time.OffsetDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookReviewResponseDto {
  private Integer id;
  private Integer userId;
  private Integer rating;
  private String feedback;
  private OffsetDateTime createdAt;

  /**
   * Deep-link key for the reviewer's profile — same reason {@code CommentResponseDto} carries one:
   * {@code /v1/api/users/{username}/profile} is keyed by username and nothing maps {@link #userId}
   * to it. Null when the reviewer's account row is gone.
   */
  private String authorUsername;

  private String authorFullName;
  private String authorProfilePictureUrl;
  private Integer authorEliteScore;
  private String authorLevelName;
}
