package com.socialapp.trending.crawler;

import java.time.OffsetDateTime;

import com.socialapp.trending.entity.enums.TrendingSource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawledItem {
  private String title;
  private String url;
  private String summary;
  private String author;
  private Integer score;
  private TrendingSource source;
  private String sourceId;
  private OffsetDateTime publishedAt;
}
