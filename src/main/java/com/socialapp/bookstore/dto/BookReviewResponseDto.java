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
}
