package com.socialapp.trending.crawler;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.socialapp.trending.entity.enums.TrendingSource;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class MediumCrawler implements TrendingCrawler {
  private final WebClient webClient;

  private static final List<String> TAGS =
      List.of(
          "programming", "software-engineering", "technology", "devops", "artificial-intelligence");

  public MediumCrawler() {
    this.webClient =
        WebClient.builder()
            .baseUrl("https://medium.com")
            .defaultHeader("Accept", "application/json")
            .build();
  }

  @Override
  public TrendingSource getSource() {
    return TrendingSource.MEDIUM;
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<CrawledItem> crawl() {
    List<CrawledItem> allItems = new ArrayList<>();

    for (String tag : TAGS) {
      try {
        String response =
            webClient
                .get()
                .uri("/feed/tag/{tag}", tag)
                .header("Accept", "application/json")
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (Objects.isNull(response)) continue;

        List<CrawledItem> items = parseRssFeed(response, tag);
        allItems.addAll(items);
      } catch (Exception e) {
        log.debug("Failed to crawl Medium tag '{}': {}", tag, e.getMessage());
      }
    }

    if (allItems.isEmpty()) {
      allItems = crawlTopFeeds();
    }

    log.info("Crawled {} items from Medium", allItems.size());
    return allItems;
  }

  @SuppressWarnings("unchecked")
  private List<CrawledItem> crawlTopFeeds() {
    List<CrawledItem> items = new ArrayList<>();
    try {
      Map<String, Object> rssJson =
          WebClient.builder()
              .baseUrl("https://api.rss2json.com/v1")
              .build()
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/api.json")
                          .queryParam("rss_url", "https://medium.com/feed/tag/programming")
                          .queryParam("count", 20)
                          .build())
              .retrieve()
              .bodyToMono(Map.class)
              .block();

      if (Objects.isNull(rssJson)) return items;

      List<Map<String, Object>> feedItems = (List<Map<String, Object>>) rssJson.get("items");
      if (Objects.isNull(feedItems)) return items;

      for (Map<String, Object> item : feedItems) {
        items.add(
            CrawledItem.builder()
                .title((String) item.get("title"))
                .url((String) item.get("link"))
                .summary((String) item.get("description"))
                .author((String) item.get("author"))
                .score(0)
                .source(TrendingSource.MEDIUM)
                .sourceId("medium_" + item.get("guid"))
                .publishedAt(OffsetDateTime.now())
                .build());
      }
    } catch (Exception e) {
      log.warn("Failed to crawl Medium via RSS: {}", e.getMessage());
    }
    return items;
  }

  private List<CrawledItem> parseRssFeed(String content, String tag) {
    // RSS feed parsing fallback
    return List.of();
  }
}
