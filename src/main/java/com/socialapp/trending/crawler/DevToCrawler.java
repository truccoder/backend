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
public class DevToCrawler implements TrendingCrawler {
  private final WebClient webClient;

  private static final String BASE_URL = "https://dev.to/api";

  public DevToCrawler() {
    this.webClient = WebClient.builder().baseUrl(BASE_URL).build();
  }

  @Override
  public TrendingSource getSource() {
    return TrendingSource.DEV_TO;
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<CrawledItem> crawl() {
    try {
      List<Map<String, Object>> articles =
          webClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/articles")
                          .queryParam("per_page", 30)
                          .queryParam("top", 7)
                          .build())
              .retrieve()
              .bodyToMono(List.class)
              .block();

      if (Objects.isNull(articles) || articles.isEmpty()) {
        return List.of();
      }

      List<CrawledItem> items = new ArrayList<>();
      for (Map<String, Object> article : articles) {
        items.add(mapToItem(article));
      }

      log.info("Crawled {} items from Dev.to", items.size());
      return items;
    } catch (Exception e) {
      log.error("Failed to crawl Dev.to: {}", e.getMessage());
      return List.of();
    }
  }

  private CrawledItem mapToItem(Map<String, Object> article) {
    String publishedAtStr = (String) article.get("published_at");
    OffsetDateTime publishedAt =
        Objects.nonNull(publishedAtStr)
            ? OffsetDateTime.parse(publishedAtStr)
            : OffsetDateTime.now();

    Number reactions = (Number) article.get("positive_reactions_count");

    return CrawledItem.builder()
        .title((String) article.get("title"))
        .url((String) article.get("url"))
        .summary((String) article.get("description"))
        .author((String) article.getOrDefault("readable_publish_date", ""))
        .score(Objects.nonNull(reactions) ? reactions.intValue() : 0)
        .source(TrendingSource.DEV_TO)
        .sourceId(String.valueOf(article.get("id")))
        .publishedAt(publishedAt)
        .build();
  }
}
