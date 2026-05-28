package com.socialapp.newsfeed.dto;

import java.time.OffsetDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedPostDataDto {
  private Integer postId;
  private Integer authorId;
  private String authorFullName;
  private String authorProfilePictureUrl;
  private String content;
  private String visibility;
  private OffsetDateTime createdAt;
  private int likeCount;
  private int commentCount;
  private int shareCount;
}
