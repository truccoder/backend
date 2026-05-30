package com.socialapp.moderation.event;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostModerationEvent {
  private Integer postId;
  private Integer authorId;
  private String content;
  private List<String> imageUrls;
  private List<Integer> taggedUserIds;
}
