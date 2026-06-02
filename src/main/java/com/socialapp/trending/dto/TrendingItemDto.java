package com.socialapp.trending.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.socialapp.trending.entity.enums.TrendingCategory;
import com.socialapp.trending.entity.enums.TrendingSource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendingItemDto {
  private Integer id;
  private String title;
  private String summary;
  private String url;
  private TrendingSource source;
  private TrendingCategory category;
  private List<String> tags;
  private Integer score;
  private String author;
  private OffsetDateTime publishedAt;
}
