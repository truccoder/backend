package com.socialapp.bookstore.dto;

import java.time.OffsetDateTime;

import com.socialapp.bookstore.entity.enums.FileFormat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookResponseDto {
  private Integer id;
  private Integer authorId;
  private Integer postId;
  private String title;
  private String description;
  private String downloadUrl;
  private String previewUrl;
  private String coverImageUrl;
  private FileFormat fileFormat;
  private Long fileSizeBytes;
  private Integer totalPages;
  private Integer previewPages;
  private Long price;
  private String currency;
  private Boolean isFree;
  private Integer downloadCount;
  private Double avgRating;
  private Integer reviewCount;
  private Boolean purchased;
  private OffsetDateTime createdAt;
}
