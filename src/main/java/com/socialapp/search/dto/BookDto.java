package com.socialapp.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookDto {
  private Integer id;
  private String title;
  private String description;
  private String coverImageUrl;
  private Integer authorId;
  private Long price;
  private Boolean isFree;
  private Double avgRating;
}
