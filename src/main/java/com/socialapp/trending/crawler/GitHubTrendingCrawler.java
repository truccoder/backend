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
public class GitHubTrendingCrawler extends AbstractTrendingCrawler {
  private static final String BASE_URL = "https://api.github.com";

  public GitHubTrendingCrawler(WebClient.Builder builder) {
    super(
        builder
            .baseUrl(BASE_URL)
            .defaultHeader("Accept", "application/vnd.github.v3+json")
            .build());
  }

  @Override
  public TrendingSource getSource() {
    return TrendingSource.GITHUB;
  }

  @Override
  @SuppressWarnings("unchecked")
  protected List<CrawledItem> fetchItems() {
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

    return items;
  }

  /**
   * GitHub's own generated card for a repository — the one that appears when a repo link is
   * pasted into a chat client.
   *
   * <p>A static URL derived from {@code full_name}, so it needs no second request and no HTML to
   * be fetched and parsed. Preferred over {@code owner.avatar_url}, which is also in the response:
   * the avatar identifies the account, while this identifies the repository, and a page of
   * trending repos from the same few large organisations would otherwise show the same picture
   * over and over.
   *
   * <p>{@code null} for a repo with no {@code full_name} — the URL would be malformed, and a
   * broken image is worse than none.
   */
  private String openGraphImage(String fullName) {
    if (Objects.isNull(fullName) || fullName.isBlank()) {
      return null;
    }
    return "https://opengraph.githubassets.com/1/" + fullName;
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
        .imageUrl(openGraphImage(fullName))
        .summary(description)
        .author(authorName)
        .score(Objects.nonNull(stars) ? stars.intValue() : 0)
        .source(TrendingSource.GITHUB)
        .sourceId(String.valueOf(repo.get("id")))
        .publishedAt(publishedAt)
        .build();
  }
}
