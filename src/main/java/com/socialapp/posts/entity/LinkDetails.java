package com.socialapp.posts.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LinkDetails {
  private String url;
  private String title;
  private String description;
  private String thumbnailUrl;
}
