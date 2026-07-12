package com.socialapp.trending.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.trending.entity.TrendingItemEntity;
import com.socialapp.trending.entity.enums.TrendingCategory;
import com.socialapp.trending.entity.enums.TrendingSource;

/**
 * Component integration tests for {@link TrendingItemRepository} against a real PostgreSQL
 * instance (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own
 * transaction, so no manual cleanup is needed. {@code t_trending_items} has no foreign key
 * constraints and no seed data.
 */
@Transactional
class TrendingItemRepositoryTest extends AbstractIntegrationTest {

  @Autowired private TrendingItemRepository trendingItemRepository;

  private static final OffsetDateTime CUTOFF = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  private static TrendingItemEntity item(
      String sourceId,
      TrendingSource source,
      TrendingCategory category,
      int score,
      OffsetDateTime publishedAt) {
    return TrendingItemEntity.builder()
        .title("Item " + sourceId)
        .url("https://example.com/" + sourceId)
        .source(source)
        .sourceId(sourceId)
        .category(category)
        .score(score)
        .publishedAt(publishedAt)
        .build();
  }

  @Nested
  @DisplayName("findByCategoryOrderByPublishedAtDesc")
  class FindByCategoryOrderByPublishedAtDesc {

    @Test
    @DisplayName("returns items of the category, newest first")
    void returnsCategoryItemsNewestFirst() {
      // Given
      TrendingItemEntity older =
          trendingItemRepository.saveAndFlush(
              item("t1", TrendingSource.GITHUB, TrendingCategory.TOOL, 10, CUTOFF));
      TrendingItemEntity newer =
          trendingItemRepository.saveAndFlush(
              item("t2", TrendingSource.GITHUB, TrendingCategory.TOOL, 10, CUTOFF.plusDays(1)));
      trendingItemRepository.saveAndFlush(
          item("t3", TrendingSource.GITHUB, TrendingCategory.CAREER, 10, CUTOFF));

      // When
      Page<TrendingItemEntity> result =
          trendingItemRepository.findByCategoryOrderByPublishedAtDesc(
              TrendingCategory.TOOL, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent())
          .extracting(TrendingItemEntity::getId)
          .containsExactly(newer.getId(), older.getId());
    }

    @Test
    @DisplayName("returns an empty page when no item matches the category")
    void returnsEmptyPageWhenNoMatch() {
      // When
      Page<TrendingItemEntity> result =
          trendingItemRepository.findByCategoryOrderByPublishedAtDesc(
              TrendingCategory.EVENT, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).isEmpty();
    }
  }

  @Nested
  @DisplayName("findTrendingSince")
  class FindTrendingSince {

    @Test
    @DisplayName("orders matches by score, then publishedAt, both descending")
    void ordersByScoreThenPublishedAt() {
      // Given
      TrendingItemEntity lowScore =
          trendingItemRepository.saveAndFlush(
              item(
                  "s1",
                  TrendingSource.HACKER_NEWS,
                  TrendingCategory.NEW_TECH,
                  5,
                  CUTOFF.plusDays(3)));
      TrendingItemEntity highScoreOlder =
          trendingItemRepository.saveAndFlush(
              item(
                  "s2",
                  TrendingSource.HACKER_NEWS,
                  TrendingCategory.NEW_TECH,
                  20,
                  CUTOFF.plusDays(1)));
      TrendingItemEntity highScoreNewer =
          trendingItemRepository.saveAndFlush(
              item(
                  "s3",
                  TrendingSource.HACKER_NEWS,
                  TrendingCategory.NEW_TECH,
                  20,
                  CUTOFF.plusDays(2)));

      // When
      Page<TrendingItemEntity> result =
          trendingItemRepository.findTrendingSince(CUTOFF, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent())
          .extracting(TrendingItemEntity::getId)
          .containsExactly(highScoreNewer.getId(), highScoreOlder.getId(), lowScore.getId());
    }

    @Test
    @DisplayName("includes an item published exactly at the cutoff (boundary: cutoff)")
    void includesItemExactlyAtCutoff() {
      // Given
      TrendingItemEntity atCutoff =
          trendingItemRepository.saveAndFlush(
              item("s1", TrendingSource.DEV_TO, TrendingCategory.TOOL, 1, CUTOFF));

      // When
      Page<TrendingItemEntity> result =
          trendingItemRepository.findTrendingSince(CUTOFF, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent())
          .extracting(TrendingItemEntity::getId)
          .containsExactly(atCutoff.getId());
    }

    @Test
    @DisplayName("excludes an item published before the cutoff (boundary: cutoff - 1s)")
    void excludesItemBeforeCutoff() {
      // Given
      trendingItemRepository.saveAndFlush(
          item("s1", TrendingSource.DEV_TO, TrendingCategory.TOOL, 1, CUTOFF.minusSeconds(1)));

      // When
      Page<TrendingItemEntity> result =
          trendingItemRepository.findTrendingSince(CUTOFF, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).isEmpty();
    }
  }

  @Nested
  @DisplayName("findTrendingByCategorySince")
  class FindTrendingByCategorySince {

    @Test
    @DisplayName("filters by category and cutoff together, ordered by score descending")
    void filtersByCategoryAndCutoff() {
      // Given
      TrendingItemEntity match =
          trendingItemRepository.saveAndFlush(
              item("c1", TrendingSource.REDDIT, TrendingCategory.MINDSET, 15, CUTOFF.plusDays(1)));
      trendingItemRepository.saveAndFlush(
          item("c2", TrendingSource.REDDIT, TrendingCategory.CAREER, 15, CUTOFF.plusDays(1)));
      trendingItemRepository.saveAndFlush(
          item("c3", TrendingSource.REDDIT, TrendingCategory.MINDSET, 15, CUTOFF.minusDays(1)));

      // When
      Page<TrendingItemEntity> result =
          trendingItemRepository.findTrendingByCategorySince(
              TrendingCategory.MINDSET, CUTOFF, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent())
          .extracting(TrendingItemEntity::getId)
          .containsExactly(match.getId());
    }
  }

  @Nested
  @DisplayName("findBySourceAndSourceId")
  class FindBySourceAndSourceId {

    @Test
    @DisplayName("finds an item by its exact source and sourceId")
    void findsExistingItem() {
      // Given
      trendingItemRepository.saveAndFlush(
          item("id-1", TrendingSource.MEDIUM, TrendingCategory.OTHER, 1, CUTOFF));

      // When
      Optional<TrendingItemEntity> result =
          trendingItemRepository.findBySourceAndSourceId(TrendingSource.MEDIUM, "id-1");

      // Then
      assertThat(result).isPresent();
    }

    @Test
    @DisplayName("returns empty when the source differs")
    void returnsEmptyWhenSourceDiffers() {
      // Given
      trendingItemRepository.saveAndFlush(
          item("id-1", TrendingSource.MEDIUM, TrendingCategory.OTHER, 1, CUTOFF));

      // When
      Optional<TrendingItemEntity> result =
          trendingItemRepository.findBySourceAndSourceId(TrendingSource.HBR, "id-1");

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("existsBySourceAndSourceId")
  class ExistsBySourceAndSourceId {

    @Test
    @DisplayName("returns true when an item with the source/sourceId pair exists")
    void returnsTrueWhenItemExists() {
      // Given
      trendingItemRepository.saveAndFlush(
          item("id-1", TrendingSource.GITHUB, TrendingCategory.TOOL, 1, CUTOFF));

      // When
      boolean result =
          trendingItemRepository.existsBySourceAndSourceId(TrendingSource.GITHUB, "id-1");

      // Then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("returns false when no item matches the source/sourceId pair")
    void returnsFalseWhenNoMatch() {
      // When
      boolean result =
          trendingItemRepository.existsBySourceAndSourceId(TrendingSource.GITHUB, "missing-id");

      // Then
      assertThat(result).isFalse();
    }
  }
}
