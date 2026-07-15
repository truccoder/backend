package com.socialapp.posts.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PollOption {
  private Integer id;
  private String text;
  private Integer votesCount;
}
