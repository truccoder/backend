package com.socialapp.bookstore.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateReviewRequestDto {
  @NotNull
  @Min(1)
  @Max(5)
  private Integer rating;

  private String feedback;
}
