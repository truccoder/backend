package com.socialapp.trending.repository;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.socialapp.trending.entity.TrendingItemEntity;
import com.socialapp.trending.entity.enums.TrendingCategory;
import com.socialapp.trending.entity.enums.TrendingSource;

public interface TrendingItemRepository extends JpaRepository<TrendingItemEntity, Integer> {

  Page<TrendingItemEntity> findByCategoryOrderByPublishedAtDesc(
      TrendingCategory category, Pageable pageable);

  @Query(
      "SELECT t FROM TrendingItemEntity t WHERE t.publishedAt >= :since"
          + " ORDER BY t.score DESC, t.publishedAt DESC")
  Page<TrendingItemEntity> findTrendingSince(OffsetDateTime since, Pageable pageable);

  @Query(
      "SELECT t FROM TrendingItemEntity t WHERE t.category = :category"
          + " AND t.publishedAt >= :since ORDER BY t.score DESC, t.publishedAt DESC")
  Page<TrendingItemEntity> findTrendingByCategorySince(
      TrendingCategory category, OffsetDateTime since, Pageable pageable);

  Optional<TrendingItemEntity> findBySourceAndSourceId(TrendingSource source, String sourceId);

  boolean existsBySourceAndSourceId(TrendingSource source, String sourceId);
}
