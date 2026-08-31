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

import com.socialapp.linkpreview.service.LinkPreviewService;
import com.socialapp.trending.entity.enums.TrendingSource;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class HackerNewsCrawler extends AbstractTrendingCrawler {
  private static final String BASE_URL = "https://hacker-news.firebaseio.com/v0";
  private static final int MAX_ITEMS = 30;
  private static final String HN_ITEM_URL = "https://news.ycombinator.com/item?id=";

  private final LinkPreviewService linkPreviewService;

  public HackerNewsCrawler(WebClient.Builder builder, LinkPreviewService linkPreviewService) {
    super(builder.baseUrl(BASE_URL).build());
    this.linkPreviewService = linkPreviewService;
  }

  @Override
  public TrendingSource getSource() {
    return TrendingSource.HACKER_NEWS;
  }

  @Override
  @SuppressWarnings("unchecked")
  protected List<CrawledItem> fetchItems() {
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

    return items;
  }

  /**
   * The picture for a story, read off the page it points at.
   *
   * <p>The odd one out among the three crawlers: dev.to and GitHub hand an image over in the JSON
   * already being fetched, while the Hacker News API carries nothing but a title, a score and a
   * link. Without this, the largest of the three sources would be the only one whose cards had no
   * picture — and the source label is the thing the trending page exists to make visible.
   *
   * <p>Costs one extra request per story, which is affordable exactly here and nowhere else: this
   * runs hourly on a scheduler thread, off any request path, in a loop that already makes one API
   * call per story. {@code findImage} swallows its own failures and applies the same timeouts,
   * size cap and address rules as the user-facing endpoint — worth having, since these URLs come
   * from whatever someone submitted to Hacker News.
   *
   * <p>Skipped for the discussion-only stories that have no external link, whose {@code url} the
   * caller has already replaced with a link back to the Hacker News thread.
   */
  private String imageFor(String url) {
    if (url.startsWith(HN_ITEM_URL)) {
      return null;
    }
    return linkPreviewService.findImage(url);
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
      url = HN_ITEM_URL + story.get("id");
    }

    return CrawledItem.builder()
        .title((String) story.get("title"))
        .url(url)
        .imageUrl(imageFor(url))
        .author((String) story.get("by"))
        .score(Objects.nonNull(score) ? score.intValue() : 0)
        .source(TrendingSource.HACKER_NEWS)
        .sourceId(String.valueOf(story.get("id")))
        .publishedAt(publishedAt)
        .build();
  }
}
