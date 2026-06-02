package com.socialapp.trending.crawler;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
public class HackerNewsCrawler implements TrendingCrawler {
  private final WebClient webClient;

  private static final String BASE_URL = "https://hacker-news.firebaseio.com/v0";
  private static final int MAX_ITEMS = 30;

  public HackerNewsCrawler() {
    this.webClient = WebClient.builder().baseUrl(BASE_URL).build();
  }

  @Override
  public TrendingSource getSource() {
    return TrendingSource.HACKER_NEWS;
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<CrawledItem> crawl() {
    try {
      List<Integer> topStoryIds =
          webClient.get().uri("/topstories.json").retrieve().bodyToMono(List.class).block();

      if (Objects.isNull(topStoryIds) || topStoryIds.isEmpty()) {
        return List.of();
      }

      List<CrawledItem> items = new ArrayList<>();
      for (int i = 0; i < Math.min(MAX_ITEMS, topStoryIds.size()); i++) {
        try {
          Map<String, Object> story =
              webClient
                  .get()
                  .uri("/item/{id}.json", topStoryIds.get(i))
                  .retrieve()
                  .bodyToMono(Map.class)
                  .block();

          if (Objects.nonNull(story) && "story".equals(story.get("type"))) {
            items.add(mapToItem(story));
          }
        } catch (Exception e) {
          log.debug("Failed to fetch HN story {}: {}", topStoryIds.get(i), e.getMessage());
        }
      }

      log.info("Crawled {} items from Hacker News", items.size());
      return items;
    } catch (Exception e) {
      log.error("Failed to crawl Hacker News: {}", e.getMessage());
      return List.of();
    }
  }

  private CrawledItem mapToItem(Map<String, Object> story) {
    Number time = (Number) story.get("time");
    OffsetDateTime publishedAt =
        Objects.nonNull(time)
            ? OffsetDateTime.ofInstant(Instant.ofEpochSecond(time.longValue()), ZoneOffset.UTC)
            : OffsetDateTime.now();

    Number score = (Number) story.get("score");
    String url = (String) story.get("url");
    if (Objects.isNull(url) || url.isBlank()) {
      url = "https://news.ycombinator.com/item?id=" + story.get("id");
    }

    return CrawledItem.builder()
        .title((String) story.get("title"))
        .url(url)
        .author((String) story.get("by"))
        .score(Objects.nonNull(score) ? score.intValue() : 0)
        .source(TrendingSource.HACKER_NEWS)
        .sourceId(String.valueOf(story.get("id")))
        .publishedAt(publishedAt)
        .build();
  }
}
