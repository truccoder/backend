package com.socialapp.search.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostDto {
  private Integer id;
  private String content;
  private String eventName;
  private Integer authorId;
  private String authorFullName;
  private String authorProfilePictureUrl;
  private String visibility;
  private LocalDateTime createdAt;

  /**
   * Populated when this post is a book post — either it matched directly, or its book's
   * title/description matched the search query.
   */
  private BookDto book;
}
