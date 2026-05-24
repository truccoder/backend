package com.socialapp.search.document;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostDocument {
  private Integer id;
  private String content;
  private Integer authorId;
  private String authorFullName;
  private String authorProfilePictureUrl;
  private String visibility;
  private LocalDateTime createdAt;
}
