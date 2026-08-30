package com.socialapp.trending.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.socialapp.trending.entity.enums.TrendingCategory;
import com.socialapp.trending.entity.enums.TrendingSource;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "t_trending_items")
@Data
@NoArgsConstructor
public class TrendingItemEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "trending_items_seq_gen")
  @SequenceGenerator(
      name = "trending_items_seq_gen",
      sequenceName = "q_trending_items_id",
      allocationSize = 1)
  private Integer id;

  private String title;

  @Column(columnDefinition = "TEXT")
  private String summary;

  private String url;

  private String imageUrl;

  @Enumerated(EnumType.STRING)
  private TrendingSource source;

  private String sourceId;

  @Enumerated(EnumType.STRING)
  private TrendingCategory category;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private List<String> tags;

  private Integer score;

  private String author;

  private OffsetDateTime publishedAt;

  @CreationTimestamp private OffsetDateTime crawledAt;

  // One field per column; a JPA entity's all-args builder constructor legitimately has this many
  // parameters, so ExcessiveParameterList doesn't point at a real design smell here.
  @SuppressWarnings("PMD.ExcessiveParameterList")
  @Builder
  public TrendingItemEntity(
      Integer id,
      String title,
      String summary,
      String url,
      String imageUrl,
      TrendingSource source,
      String sourceId,
      TrendingCategory category,
      List<String> tags,
      Integer score,
      String author,
      OffsetDateTime publishedAt,
      OffsetDateTime crawledAt) {
    this.id = id;
    this.title = title;
    this.summary = summary;
    this.url = url;
    this.imageUrl = imageUrl;
    this.source = source;
    this.sourceId = sourceId;
    this.category = category;
    this.tags = tags == null ? null : new ArrayList<>(tags);
    this.score = score;
    this.author = author;
    this.publishedAt = publishedAt;
    this.crawledAt = crawledAt;
  }

  public List<String> getTags() {
    return tags == null ? List.of() : List.copyOf(tags);
  }

  public void setTags(List<String> tags) {
    this.tags = tags == null ? null : new ArrayList<>(tags);
  }
}
