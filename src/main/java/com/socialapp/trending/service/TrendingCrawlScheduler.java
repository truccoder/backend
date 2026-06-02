package com.socialapp.trending.service;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.socialapp.trending.crawler.CrawledItem;
import com.socialapp.trending.crawler.TrendingCrawler;
import com.socialapp.trending.entity.TrendingItemEntity;
import com.socialapp.trending.entity.enums.TrendingCategory;
import com.socialapp.trending.repository.TrendingItemRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrendingCrawlScheduler {
  private final List<TrendingCrawler> crawlers;
  private final TrendingClassificationService classificationService;
  private final TrendingItemRepository trendingItemRepository;

  @Scheduled(fixedRateString = "${trending.crawl-interval-ms:3600000}")
  public void crawlAll() {
    log.info("Starting trending crawl cycle with {} crawlers", crawlers.size());

    for (TrendingCrawler crawler : crawlers) {
      try {
        List<CrawledItem> items = crawler.crawl();
        List<CrawledItem> newItems =
            items.stream()
                .filter(
                    item ->
                        !trendingItemRepository.existsBySourceAndSourceId(
                            item.getSource(), item.getSourceId()))
                .toList();

        if (newItems.isEmpty()) {
          log.debug("No new items from {}", crawler.getSource());
          continue;
        }

        List<TrendingCategory> categories = classificationService.classifyBatch(newItems);

        for (int i = 0; i < newItems.size(); i++) {
          CrawledItem crawled = newItems.get(i);
          TrendingCategory category = categories.get(i);
          saveItem(crawled, category);
        }

        log.info("Saved {} new items from {}", newItems.size(), crawler.getSource());
      } catch (Exception e) {
        log.error("Crawl failed for {}: {}", crawler.getSource(), e.getMessage());
      }
    }
  }

  private void saveItem(CrawledItem crawled, TrendingCategory category) {
    TrendingItemEntity entity =
        TrendingItemEntity.builder()
            .title(crawled.getTitle())
            .summary(truncate(crawled.getSummary(), 500))
            .url(crawled.getUrl())
            .source(crawled.getSource())
            .sourceId(crawled.getSourceId())
            .category(category)
            .score(crawled.getScore())
            .author(crawled.getAuthor())
            .publishedAt(crawled.getPublishedAt())
            .build();

    trendingItemRepository.save(entity);
  }

  private String truncate(String text, int maxLength) {
    if (text == null || text.length() <= maxLength) return text;
    return text.substring(0, maxLength);
  }
}
