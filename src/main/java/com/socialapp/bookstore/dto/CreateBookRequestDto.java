package com.socialapp.bookstore.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateBookRequestDto {
  @NotBlank private String title;

  private String description;

  private Integer postId;

  @Min(0)
  private Long price;

  private Integer previewPages;
}
