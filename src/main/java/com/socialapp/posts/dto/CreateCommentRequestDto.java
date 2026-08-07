package com.socialapp.posts.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateCommentRequestDto {
  @NotBlank(message = "Comment content must not be blank")
  private String content;

  private Integer parentId;
}
