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
public class RedditCrawler implements TrendingCrawler {
  private final WebClient webClient;

  private static final String BASE_URL = "https://www.reddit.com";
  private static final List<String> SUBREDDITS =
      List.of("programming", "technology", "opensource", "ExperiencedDevs");

  public RedditCrawler() {
    this.webClient =
        WebClient.builder()
            .baseUrl(BASE_URL)
            .defaultHeader("User-Agent", "SocialApp/1.0 (Tech Trending Crawler)")
            .build();
  }

  @Override
  public TrendingSource getSource() {
    return TrendingSource.REDDIT;
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<CrawledItem> crawl() {
    List<CrawledItem> allItems = new ArrayList<>();

    for (String subreddit : SUBREDDITS) {
      try {
        Map<String, Object> response =
            webClient
                .get()
                .uri("/r/{subreddit}/hot.json?limit=10", subreddit)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (Objects.isNull(response)) continue;

        Map<String, Object> data = (Map<String, Object>) response.get("data");
        if (Objects.isNull(data)) continue;

        List<Map<String, Object>> children = (List<Map<String, Object>>) data.get("children");
        if (Objects.isNull(children)) continue;

        for (Map<String, Object> child : children) {
          Map<String, Object> postData = (Map<String, Object>) child.get("data");
          if (Objects.nonNull(postData) && !Boolean.TRUE.equals(postData.get("stickied"))) {
            allItems.add(mapToItem(postData, subreddit));
          }
        }
      } catch (Exception e) {
        log.warn("Failed to crawl r/{}: {}", subreddit, e.getMessage());
      }
    }

    log.info("Crawled {} items from Reddit", allItems.size());
    return allItems;
  }

  private CrawledItem mapToItem(Map<String, Object> post, String subreddit) {
    Number createdUtc = (Number) post.get("created_utc");
    OffsetDateTime publishedAt =
        Objects.nonNull(createdUtc)
            ? OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(createdUtc.longValue()), ZoneOffset.UTC)
            : OffsetDateTime.now();

    Number score = (Number) post.get("score");
    String permalink = (String) post.get("permalink");
    String url =
        Objects.nonNull(permalink)
            ? "https://www.reddit.com" + permalink
            : (String) post.get("url");

    return CrawledItem.builder()
        .title((String) post.get("title"))
        .url(url)
        .summary((String) post.get("selftext"))
        .author((String) post.get("author"))
        .score(Objects.nonNull(score) ? score.intValue() : 0)
        .source(TrendingSource.REDDIT)
        .sourceId("reddit_" + subreddit + "_" + post.get("id"))
        .publishedAt(publishedAt)
        .build();
  }
}
