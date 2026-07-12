package com.socialapp.trending.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.trending.crawler.CrawledItem;
import com.socialapp.trending.crawler.TrendingCrawler;
import com.socialapp.trending.entity.TrendingItemEntity;
import com.socialapp.trending.entity.enums.TrendingCategory;
import com.socialapp.trending.entity.enums.TrendingSource;
import com.socialapp.trending.repository.TrendingItemRepository;

/**
 * Component (unit) tests for {@link TrendingCrawlScheduler}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3 BDD
 * Given/When/Then) — see {@code PostServiceTest} for the full rationale.
 *
 * <p>The scheduler takes its {@code List<TrendingCrawler>} via constructor injection, which
 * {@code @InjectMocks} cannot populate automatically (it only wires individually-annotated {@code
 * @Mock} fields, not a collection of them) — so each test builds the crawler list explicitly and
 * constructs the scheduler by hand.
 */
@ExtendWith(MockitoExtension.class)
class TrendingCrawlSchedulerTest {

  @Mock private TrendingClassificationService classificationService;
  @Mock private TrendingItemRepository trendingItemRepository;
  @Mock private TrendingCrawler crawlerA;
  @Mock private TrendingCrawler crawlerB;

  @Captor private ArgumentCaptor<TrendingItemEntity> savedCaptor;

  @BeforeEach
  void setUp() {
    lenientSourceStub();
  }

  private void lenientSourceStub() {
    org.mockito.Mockito.lenient().when(crawlerA.getSource()).thenReturn(TrendingSource.HACKER_NEWS);
    org.mockito.Mockito.lenient().when(crawlerB.getSource()).thenReturn(TrendingSource.DEV_TO);
  }

  private static CrawledItem crawled(String sourceId, String summary) {
    return CrawledItem.builder()
        .title("Title " + sourceId)
        .url("https://example.com/" + sourceId)
        .summary(summary)
        .source(TrendingSource.HACKER_NEWS)
        .sourceId(sourceId)
        .score(10)
        .build();
  }

  // =====================================================================
  // crawlAll
  // =====================================================================

  @Nested
  @DisplayName("crawlAll")
  class CrawlAllTests {

    @Test
    @DisplayName("should skip a crawler that returns no new items")
    void shouldSkipCrawler_whenNoNewItemsFound() {
      // Given
      when(crawlerA.crawl()).thenReturn(List.of(crawled("1", "s")));
      when(trendingItemRepository.existsBySourceAndSourceId(TrendingSource.HACKER_NEWS, "1"))
          .thenReturn(true);
      TrendingCrawlScheduler scheduler =
          new TrendingCrawlScheduler(
              List.of(crawlerA), classificationService, trendingItemRepository);

      // When
      scheduler.crawlAll();

      // Then
      verify(classificationService, never()).classifyBatch(any());
      verify(trendingItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("should classify and save every genuinely new item")
    void shouldSaveNewItems_withClassifiedCategories() {
      // Given
      CrawledItem item1 = crawled("1", "short summary");
      when(crawlerA.crawl()).thenReturn(List.of(item1));
      when(trendingItemRepository.existsBySourceAndSourceId(TrendingSource.HACKER_NEWS, "1"))
          .thenReturn(false);
      when(classificationService.classifyBatch(List.of(item1)))
          .thenReturn(List.of(TrendingCategory.TOOL));
      TrendingCrawlScheduler scheduler =
          new TrendingCrawlScheduler(
              List.of(crawlerA), classificationService, trendingItemRepository);

      // When
      scheduler.crawlAll();

      // Then
      verify(trendingItemRepository).save(savedCaptor.capture());
      assertThat(savedCaptor.getValue().getSourceId()).isEqualTo("1");
      assertThat(savedCaptor.getValue().getCategory()).isEqualTo(TrendingCategory.TOOL);
      assertThat(savedCaptor.getValue().getSummary()).isEqualTo("short summary");
    }

    @Test
    @DisplayName("should filter out items that already exist before classifying")
    void shouldFilterOutExistingItems_beforeClassifying() {
      // Given
      CrawledItem existing = crawled("1", "s");
      CrawledItem fresh = crawled("2", "s");
      when(crawlerA.crawl()).thenReturn(List.of(existing, fresh));
      when(trendingItemRepository.existsBySourceAndSourceId(TrendingSource.HACKER_NEWS, "1"))
          .thenReturn(true);
      when(trendingItemRepository.existsBySourceAndSourceId(TrendingSource.HACKER_NEWS, "2"))
          .thenReturn(false);
      when(classificationService.classifyBatch(List.of(fresh)))
          .thenReturn(List.of(TrendingCategory.TOOL));
      TrendingCrawlScheduler scheduler =
          new TrendingCrawlScheduler(
              List.of(crawlerA), classificationService, trendingItemRepository);

      // When
      scheduler.crawlAll();

      // Then
      verify(trendingItemRepository, org.mockito.Mockito.times(1)).save(any());
      verify(classificationService).classifyBatch(List.of(fresh));
    }

    @Test
    @DisplayName("should continue with the next crawler when one throws")
    void shouldContinueToNextCrawler_whenOneCrawlerThrows() {
      // Given
      when(crawlerA.crawl()).thenThrow(new RuntimeException("network down"));
      CrawledItem item = crawled("1", "s");
      when(crawlerB.crawl()).thenReturn(List.of(item));
      when(trendingItemRepository.existsBySourceAndSourceId(TrendingSource.HACKER_NEWS, "1"))
          .thenReturn(false);
      when(classificationService.classifyBatch(List.of(item)))
          .thenReturn(List.of(TrendingCategory.TOOL));
      TrendingCrawlScheduler scheduler =
          new TrendingCrawlScheduler(
              List.of(crawlerA, crawlerB), classificationService, trendingItemRepository);

      // When / Then
      org.assertj.core.api.Assertions.assertThatCode(scheduler::crawlAll)
          .doesNotThrowAnyException();
      verify(trendingItemRepository).save(any());
    }

    @Test
    @DisplayName("should not truncate a null summary")
    void shouldNotTruncate_whenSummaryIsNull() {
      // Given
      CrawledItem item = crawled("1", null);
      when(crawlerA.crawl()).thenReturn(List.of(item));
      when(trendingItemRepository.existsBySourceAndSourceId(TrendingSource.HACKER_NEWS, "1"))
          .thenReturn(false);
      when(classificationService.classifyBatch(List.of(item)))
          .thenReturn(List.of(TrendingCategory.TOOL));
      TrendingCrawlScheduler scheduler =
          new TrendingCrawlScheduler(
              List.of(crawlerA), classificationService, trendingItemRepository);

      // When
      scheduler.crawlAll();

      // Then
      verify(trendingItemRepository).save(savedCaptor.capture());
      assertThat(savedCaptor.getValue().getSummary()).isNull();
    }

    @Test
    @DisplayName("should not truncate a summary within the 500-char limit")
    void shouldNotTruncate_whenSummaryWithinLimit() {
      // Given
      String summary = "a".repeat(100);
      CrawledItem item = crawled("1", summary);
      when(crawlerA.crawl()).thenReturn(List.of(item));
      when(trendingItemRepository.existsBySourceAndSourceId(TrendingSource.HACKER_NEWS, "1"))
          .thenReturn(false);
      when(classificationService.classifyBatch(List.of(item)))
          .thenReturn(List.of(TrendingCategory.TOOL));
      TrendingCrawlScheduler scheduler =
          new TrendingCrawlScheduler(
              List.of(crawlerA), classificationService, trendingItemRepository);

      // When
      scheduler.crawlAll();

      // Then
      verify(trendingItemRepository).save(savedCaptor.capture());
      assertThat(savedCaptor.getValue().getSummary()).hasSize(100);
    }

    @Test
    @DisplayName("should truncate a summary exceeding the 500-char limit")
    void shouldTruncate_whenSummaryExceedsLimit() {
      // Given
      String summary = "a".repeat(600);
      CrawledItem item = crawled("1", summary);
      when(crawlerA.crawl()).thenReturn(List.of(item));
      when(trendingItemRepository.existsBySourceAndSourceId(TrendingSource.HACKER_NEWS, "1"))
          .thenReturn(false);
      when(classificationService.classifyBatch(List.of(item)))
          .thenReturn(List.of(TrendingCategory.TOOL));
      TrendingCrawlScheduler scheduler =
          new TrendingCrawlScheduler(
              List.of(crawlerA), classificationService, trendingItemRepository);

      // When
      scheduler.crawlAll();

      // Then
      verify(trendingItemRepository).save(savedCaptor.capture());
      assertThat(savedCaptor.getValue().getSummary()).hasSize(500);
    }
  }
}
