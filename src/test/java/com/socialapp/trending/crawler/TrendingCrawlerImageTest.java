package com.socialapp.trending.crawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import com.socialapp.linkpreview.service.LinkPreviewService;

import reactor.core.publisher.Mono;

/**
 * Component tests for the picture each crawler attaches to a trending item, per ISTQB CTFL v4.0.1
 * Section 2.2.2. The three sources are stubbed at the {@code ExchangeFunction} level, so the real
 * parsing runs against real response shapes without any network.
 *
 * <p>One class for all three because the behaviour under test is one decision made three ways:
 * where the picture for a trending card comes from. dev.to and GitHub hand one over in the JSON
 * already being fetched; Hacker News hands over nothing and has to be read off the target page.
 */
class TrendingCrawlerImageTest {

  private static WebClient.Builder respondingWith(ExchangeFunction exchangeFunction) {
    return WebClient.builder().exchangeFunction(exchangeFunction);
  }

  private static ClientResponse json(String body) {
    return ClientResponse.create(HttpStatus.OK)
        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .body(body)
        .build();
  }

  @Nested
  @DisplayName("DevToCrawler")
  class DevToTests {

    private DevToCrawler crawlerReturning(String articlesJson) {
      return new DevToCrawler(respondingWith(request -> Mono.just(json(articlesJson))));
    }

    @Test
    @DisplayName("shouldUseTheAuthorsOwnCoverImage_happyPath")
    void shouldPreferCoverImage() {
      // Given — both fields present. The chosen cover says more about an article than a
      // generated card rendering its title does.
      List<CrawledItem> items =
          crawlerReturning(
                  """
                  [{"id":1,"title":"T","url":"https://dev.to/a","description":"d",
                    "positive_reactions_count":5,"published_at":"2026-08-01T00:00:00Z",
                    "cover_image":"https://cdn.dev.to/cover.png",
                    "social_image":"https://cdn.dev.to/social.png"}]
                  """)
              .crawl();

      // Then
      assertThat(items).hasSize(1);
      assertThat(items.get(0).getImageUrl()).isEqualTo("https://cdn.dev.to/cover.png");
    }

    @Test
    @DisplayName("shouldFallBackToTheGeneratedSocialCard_whenNoCoverWasChosen")
    void shouldFallBackToSocialImage() {
      // Given — cover_image is null for most articles; social_image is almost always present,
      // which is what makes it the fallback rather than the first choice
      List<CrawledItem> items =
          crawlerReturning(
                  """
                  [{"id":1,"title":"T","url":"https://dev.to/a","description":"d",
                    "positive_reactions_count":5,"published_at":"2026-08-01T00:00:00Z",
                    "cover_image":null,"social_image":"https://cdn.dev.to/social.png"}]
                  """)
              .crawl();

      // Then
      assertThat(items.get(0).getImageUrl()).isEqualTo("https://cdn.dev.to/social.png");
    }

    @Test
    @DisplayName("shouldLeaveTheImageNull_whenTheArticleHasNeither")
    void shouldLeaveImageNull() {
      // Given — a blank string must read as absent, not as an image URL of length zero
      List<CrawledItem> items =
          crawlerReturning(
                  """
                  [{"id":1,"title":"T","url":"https://dev.to/a","description":"d",
                    "positive_reactions_count":5,"published_at":"2026-08-01T00:00:00Z",
                    "cover_image":"","social_image":""}]
                  """)
              .crawl();

      // Then
      assertThat(items.get(0).getImageUrl()).isNull();
    }
  }

  @Nested
  @DisplayName("GitHubTrendingCrawler")
  class GitHubTests {

    private GitHubTrendingCrawler crawlerReturning(String searchJson) {
      return new GitHubTrendingCrawler(respondingWith(request -> Mono.just(json(searchJson))));
    }

    @Test
    @DisplayName("shouldUseTheRepositorysOwnOpenGraphCard_happyPath")
    void shouldUseOpenGraphCard() {
      // Given — a static URL derived from full_name, so no second request and no HTML parsing.
      // Preferred over owner.avatar_url, which is also in this response: a page of repos from the
      // same few large organisations would otherwise show one picture over and over.
      List<CrawledItem> items =
          crawlerReturning(
                  """
                  {"items":[{"id":9,"full_name":"acme/widget","html_url":"https://github.com/acme/widget",
                    "description":"d","stargazers_count":120,"created_at":"2026-08-01T00:00:00Z",
                    "owner":{"login":"acme","avatar_url":"https://avatars.github.com/acme"}}]}
                  """)
              .crawl();

      // Then
      assertThat(items).hasSize(1);
      assertThat(items.get(0).getImageUrl())
          .isEqualTo("https://opengraph.githubassets.com/1/acme/widget");
    }

    @Test
    @DisplayName("shouldLeaveTheImageNull_whenTheRepoHasNoFullName")
    void shouldLeaveImageNullWithoutFullName() {
      // Given — the derived URL would be malformed, and a broken image is worse than none
      List<CrawledItem> items =
          crawlerReturning(
                  """
                  {"items":[{"id":9,"full_name":null,"html_url":"https://github.com/x",
                    "description":"d","stargazers_count":1,"created_at":"2026-08-01T00:00:00Z",
                    "owner":{"login":"x"}}]}
                  """)
              .crawl();

      // Then
      assertThat(items.get(0).getImageUrl()).isNull();
    }
  }

  @Nested
  @DisplayName("HackerNewsCrawler")
  class HackerNewsTests {

    private HackerNewsCrawler crawlerFor(String storyJson, LinkPreviewService linkPreviewService) {
      ExchangeFunction exchangeFunction =
          request ->
              Mono.just(json(request.url().getPath().contains("topstories") ? "[101]" : storyJson));
      return new HackerNewsCrawler(respondingWith(exchangeFunction), linkPreviewService);
    }

    @Test
    @DisplayName("shouldReadTheImageOffTheStorysTargetPage_happyPath")
    void shouldReadImageFromTargetPage() {
      // Given — the API carries a title, a score and a link and nothing else. Without this the
      // largest of the three sources would be the only one whose cards had no picture.
      LinkPreviewService linkPreviewService = mock(LinkPreviewService.class);
      when(linkPreviewService.findImage("https://blog.example.com/post"))
          .thenReturn("https://blog.example.com/og.png");

      // When
      List<CrawledItem> items =
          crawlerFor(
                  """
                  {"id":101,"type":"story","title":"T","by":"u","score":80,
                   "time":1785000000,"url":"https://blog.example.com/post"}
                  """,
                  linkPreviewService)
              .crawl();

      // Then
      assertThat(items).hasSize(1);
      assertThat(items.get(0).getImageUrl()).isEqualTo("https://blog.example.com/og.png");
    }

    @Test
    @DisplayName("shouldNotFetchAnythingForADiscussionOnlyStory")
    void shouldSkipDiscussionOnlyStories() {
      // Given — an Ask HN post has no external link, so the crawler has already pointed its url
      // back at the Hacker News thread. Fetching that would spend a request to read the site the
      // story came from.
      LinkPreviewService linkPreviewService = mock(LinkPreviewService.class);

      // When
      List<CrawledItem> items =
          crawlerFor(
                  """
                  {"id":101,"type":"story","title":"Ask HN: anything?","by":"u","score":80,
                   "time":1785000000}
                  """,
                  linkPreviewService)
              .crawl();

      // Then
      assertThat(items.get(0).getUrl()).isEqualTo("https://news.ycombinator.com/item?id=101");
      assertThat(items.get(0).getImageUrl()).isNull();
      verify(linkPreviewService, never()).findImage(anyString());
    }

    @Test
    @DisplayName("shouldKeepTheStory_whenTheTargetPageHasNoImage")
    void shouldKeepStoryWithoutImage() {
      // Given — a page with no metadata is the common case, not a failure
      LinkPreviewService linkPreviewService = mock(LinkPreviewService.class);
      when(linkPreviewService.findImage(anyString())).thenReturn(null);

      // When
      List<CrawledItem> items =
          crawlerFor(
                  """
                  {"id":101,"type":"story","title":"T","by":"u","score":80,
                   "time":1785000000,"url":"https://blog.example.com/post"}
                  """,
                  linkPreviewService)
              .crawl();

      // Then
      assertThat(items).hasSize(1);
      assertThat(items.get(0).getImageUrl()).isNull();
    }
  }
}
