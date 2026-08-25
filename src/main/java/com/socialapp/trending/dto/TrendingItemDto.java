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

  /**
   * A picture for the card, or {@code null} when the source gave none.
   *
   * <p>The column has existed since {@code V12} and nothing ever wrote to it, so every trending
   * card rendered as a block of text — on the one surface in the product whose content nobody
   * here wrote, and which therefore has the least to distinguish one row from the next.
   *
   * <p>Filled by the crawler at collection time, not resolved on read. Two of the three sources
   * already hand it over in the JSON being fetched anyway (see {@code DevToCrawler} and {@code
   * GitHubTrendingCrawler}); reading it later would mean fetching a stranger's page from inside a
   * request the reader is waiting on.
   *
   * <p>Points at the source's own host, so it can 403 or disappear — clients need a fallback for
   * a broken image regardless, since {@code null} is a normal value here (Hacker News supplies
   * nothing).
   */
  private String imageUrl;

  private TrendingSource source;
  private TrendingCategory category;
  private List<String> tags;
  private Integer score;
  private String author;
  private OffsetDateTime publishedAt;
}
