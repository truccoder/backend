package com.socialapp.posts.dto;

import lombok.Data;

@Data
public class CreateCommentRequestDto {
  private String content;
  private Integer parentId;
}
