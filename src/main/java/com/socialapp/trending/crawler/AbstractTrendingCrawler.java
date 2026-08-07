package com.socialapp.trending.crawler;

import java.util.List;

import org.springframework.web.reactive.function.client.WebClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Template Method base for the source-specific crawlers: each subclass builds its own {@link
 * WebClient} (headers differ per source) and implements {@link #fetchItems()} with its
 * source-specific fetch/parse logic; this class owns the shared safety net (log the crawled
 * count, swallow any unexpected failure as an empty result rather than letting it escape to the
 * scheduler) so every crawler behaves consistently.
 */
@Slf4j
public abstract class AbstractTrendingCrawler implements TrendingCrawler {
  protected final WebClient webClient;

  protected AbstractTrendingCrawler(WebClient webClient) {
    this.webClient = webClient;
  }

  @Override
  public final List<CrawledItem> crawl() {
    try {
      List<CrawledItem> items = fetchItems();
      log.info("Crawled {} items from {}", items.size(), getSource());
      return items;
    } catch (Exception e) {
      log.error("Failed to crawl {}: {}", getSource(), e.getMessage());
      return List.of();
    }
  }

  protected abstract List<CrawledItem> fetchItems();
}
