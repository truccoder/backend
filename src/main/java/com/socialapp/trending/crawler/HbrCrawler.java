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
public class HbrCrawler implements TrendingCrawler {
  private final WebClient webClient;

  private static final List<String> HBR_FEEDS =
      List.of(
          "https://hbr.org/feed/topic/technology",
          "https://hbr.org/feed/topic/innovation",
          "https://hbr.org/feed/topic/leadership");

  public HbrCrawler() {
    this.webClient = WebClient.builder().baseUrl("https://api.rss2json.com/v1").build();
  }

  @Override
  public TrendingSource getSource() {
    return TrendingSource.HBR;
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<CrawledItem> crawl() {
    List<CrawledItem> allItems = new ArrayList<>();

    for (String feedUrl : HBR_FEEDS) {
      try {
        Map<String, Object> response =
            webClient
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/api.json")
                            .queryParam("rss_url", feedUrl)
                            .queryParam("count", 10)
                            .build())
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (Objects.isNull(response)) continue;

        List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("items");
        if (Objects.isNull(items)) continue;

        for (Map<String, Object> item : items) {
          allItems.add(mapToItem(item));
        }
      } catch (Exception e) {
        log.debug("Failed to crawl HBR feed '{}': {}", feedUrl, e.getMessage());
      }
    }

    log.info("Crawled {} items from HBR", allItems.size());
    return allItems;
  }

  private CrawledItem mapToItem(Map<String, Object> item) {
    String pubDate = (String) item.get("pubDate");
    OffsetDateTime publishedAt = OffsetDateTime.now();
    if (Objects.nonNull(pubDate) && !pubDate.isBlank()) {
      try {
        publishedAt = OffsetDateTime.parse(pubDate);
      } catch (Exception ignored) {
      }
    }

    String description = (String) item.get("description");
    if (Objects.nonNull(description) && description.length() > 500) {
      description = description.substring(0, 500);
    }

    return CrawledItem.builder()
        .title((String) item.get("title"))
        .url((String) item.get("link"))
        .summary(description)
        .author((String) item.get("author"))
        .score(0)
        .source(TrendingSource.HBR)
        .sourceId("hbr_" + Objects.toString(item.get("guid"), String.valueOf(item.hashCode())))
        .publishedAt(publishedAt)
        .build();
  }
}
