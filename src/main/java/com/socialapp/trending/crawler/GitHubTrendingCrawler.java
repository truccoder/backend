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
public class GitHubTrendingCrawler implements TrendingCrawler {
  private final WebClient webClient;

  private static final String BASE_URL = "https://api.github.com";

  public GitHubTrendingCrawler() {
    this.webClient =
        WebClient.builder()
            .baseUrl(BASE_URL)
            .defaultHeader("Accept", "application/vnd.github.v3+json")
            .build();
  }

  @Override
  public TrendingSource getSource() {
    return TrendingSource.GITHUB;
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<CrawledItem> crawl() {
    try {
      String created = OffsetDateTime.now().minusDays(7).toLocalDate().toString();

      Map<String, Object> response =
          webClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/search/repositories")
                          .queryParam("q", "created:>" + created)
                          .queryParam("sort", "stars")
                          .queryParam("order", "desc")
                          .queryParam("per_page", 30)
                          .build())
              .retrieve()
              .bodyToMono(Map.class)
              .block();

      if (Objects.isNull(response)) {
        return List.of();
      }

      List<Map<String, Object>> repos = (List<Map<String, Object>>) response.get("items");
      if (Objects.isNull(repos) || repos.isEmpty()) {
        return List.of();
      }

      List<CrawledItem> items = new ArrayList<>();
      for (Map<String, Object> repo : repos) {
        items.add(mapToItem(repo));
      }

      log.info("Crawled {} items from GitHub Trending", items.size());
      return items;
    } catch (Exception e) {
      log.error("Failed to crawl GitHub Trending: {}", e.getMessage());
      return List.of();
    }
  }

  @SuppressWarnings("unchecked")
  private CrawledItem mapToItem(Map<String, Object> repo) {
    String createdAt = (String) repo.get("created_at");
    OffsetDateTime publishedAt =
        Objects.nonNull(createdAt) ? OffsetDateTime.parse(createdAt) : OffsetDateTime.now();

    Number stars = (Number) repo.get("stargazers_count");
    Map<String, Object> owner = (Map<String, Object>) repo.get("owner");
    String authorName = Objects.nonNull(owner) ? (String) owner.get("login") : "";

    String description = (String) repo.get("description");
    String fullName = (String) repo.get("full_name");

    return CrawledItem.builder()
        .title(fullName)
        .url((String) repo.get("html_url"))
        .summary(description)
        .author(authorName)
        .score(Objects.nonNull(stars) ? stars.intValue() : 0)
        .source(TrendingSource.GITHUB)
        .sourceId(String.valueOf(repo.get("id")))
        .publishedAt(publishedAt)
        .build();
  }
}
