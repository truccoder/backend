package com.socialapp.trending.repository;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.trending.entity.TrendingItemEntity;
import com.socialapp.trending.entity.enums.TrendingCategory;
import com.socialapp.trending.entity.enums.TrendingSource;

public interface TrendingItemRepository extends JpaRepository<TrendingItemEntity, Integer> {

  Page<TrendingItemEntity> findByCategoryOrderByPublishedAtDesc(
      TrendingCategory category, Pageable pageable);

  /**
   * One page of what is trending, ranked so that the three sources can share a list.
   *
   * <p><b>Why not {@code ORDER BY score}.</b> The three crawlers do not measure the same thing on
   * the same scale: a GitHub score is a star count in the hundreds of thousands, a Hacker News
   * score is upvotes in the thousands, a Dev.to score is reactions in the hundreds. Sorting the raw
   * column puts every GitHub row above every other row regardless of how each performed on its own
   * platform — the database held Hacker News 52 · Dev.to 36 · GitHub 34 and page one was almost
   * entirely GitHub. The product's claim is that it reads three sources; raw sort made two of them
   * invisible while the data was fine.
   *
   * <p><b>{@code PERCENT_RANK}, not {@code score / MAX(score)}.</b> Dividing by the per-source
   * maximum is scale-free but not outlier-free: one runaway repository with 400k stars compresses
   * every other GitHub row towards zero and flips the problem round, hiding the source that used to
   * dominate. Percent rank asks only where an item sits among its own source's items in this
   * window, so a source's best is always its best whatever the absolute numbers are.
   *
   * <p>The window is computed <i>after</i> the {@code since}/{@code category}/{@code source}
   * filters, inside the subquery — an item's standing is its standing among the rows the caller is
   * actually looking at, not among all rows ever crawled. When {@code source} is given, every row
   * shares one partition and the ranking degenerates to score order, which is the right answer for
   * a single-source list.
   *
   * <p>Columns are listed rather than {@code SELECT r.*} because {@code r} also carries the
   * computed rank, and this result set is mapped onto the entity.
   *
   * <p>Native, and both optional filters go through {@code cast(:x as varchar)}: Postgres cannot
   * infer a type for a bare parameter compared against NULL, and both columns store the enum's
   * {@code name()} as varchar.
   */
  @Query(
      value =
          """
          SELECT r.id, r.title, r.summary, r.url, r.image_url, r.source, r.source_id,
                 r.category, r.tags, r.score, r.author, r.published_at, r.crawled_at
            FROM (
                  SELECT ti.*,
                         PERCENT_RANK() OVER (PARTITION BY ti.source ORDER BY ti.score) AS source_rank
                    FROM socialapp.t_trending_items ti
                   WHERE ti.published_at >= :since
                     AND (cast(:category as varchar) IS NULL OR ti.category = cast(:category as varchar))
                     AND (cast(:source as varchar) IS NULL OR ti.source = cast(:source as varchar))
                 ) r
           ORDER BY r.source_rank DESC, r.score DESC, r.published_at DESC, r.id DESC
          """,
      countQuery =
          """
          SELECT COUNT(*) FROM socialapp.t_trending_items ti
           WHERE ti.published_at >= :since
             AND (cast(:category as varchar) IS NULL OR ti.category = cast(:category as varchar))
             AND (cast(:source as varchar) IS NULL OR ti.source = cast(:source as varchar))
          """,
      nativeQuery = true)
  Page<TrendingItemEntity> findTrendingNormalized(
      @Param("since") OffsetDateTime since,
      @Param("category") String category,
      @Param("source") String source,
      Pageable pageable);

  Optional<TrendingItemEntity> findBySourceAndSourceId(TrendingSource source, String sourceId);

  boolean existsBySourceAndSourceId(TrendingSource source, String sourceId);
}
