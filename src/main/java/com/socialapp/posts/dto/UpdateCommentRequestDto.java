package com.socialapp.posts.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateCommentRequestDto {
  @NotBlank(message = "Comment content must not be blank")
  private String content;
}
