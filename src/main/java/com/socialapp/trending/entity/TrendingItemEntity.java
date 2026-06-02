package com.socialapp.trending.entity;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.socialapp.trending.entity.enums.TrendingCategory;
import com.socialapp.trending.entity.enums.TrendingSource;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_trending_items")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
  private List<String> tags;

  private Integer score;

  private String author;

  private OffsetDateTime publishedAt;

  @CreationTimestamp private OffsetDateTime crawledAt;
}
