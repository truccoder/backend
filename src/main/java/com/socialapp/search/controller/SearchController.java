package com.socialapp.search.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.common.utils.Constants;
import com.socialapp.friendships.service.FriendshipService;
import com.socialapp.matchmaking.dto.ProjectResponseDto;
import com.socialapp.matchmaking.service.ProjectQueryService;
import com.socialapp.roadmap.dto.RoadmapDto;
import com.socialapp.roadmap.service.RoadmapService;
import com.socialapp.search.dto.BookDto;
import com.socialapp.search.dto.MentionSuggestionDto;
import com.socialapp.search.dto.PostDto;
import com.socialapp.search.dto.SearchResponse;
import com.socialapp.search.dto.SuggestionDto;
import com.socialapp.search.dto.UserDto;
import com.socialapp.search.service.MentionSuggestService;
import com.socialapp.search.service.SearchService;
import com.socialapp.search.service.SuggestService;
import com.socialapp.search.util.SearchQuerySanitizer;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/**
 * Single-call unified search, directly against Postgres (t_users, t_posts, t_books) — no search
 * index. Three lists come back from one query string: people, posts, books.
 *
 * <p>A matching book appears twice on purpose — once in {@code books}, and once as the post it was
 * published as, with its details inline. See {@link SearchResponse} for why both are the right
 * answer to different questions the same typed string can be asking.
 */
@Validated
@RestController
@RequestMapping("/v1/api/search")
@RequiredArgsConstructor
public class SearchController {
  private final SearchService searchService;
  private final SuggestService suggestService;
  private final FriendshipService friendshipService;
  private final MentionSuggestService mentionSuggestService;
  private final ProjectQueryService projectQueryService;
  private final RoadmapService roadmapService;

  @GetMapping
  public SearchResponse search(
      @RequestParam @NotBlank String q,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_SEARCH_PAGE_SIZE)
          @Positive
          @Max(Constants.MAX_PAGINATION_PAGE_SIZE)
          int size) {
    Integer currentUserId = SecurityUtils.getCurrentUserId();
    List<Integer> friendIds = friendshipService.getFriendIds(currentUserId);

    List<UserDto> users =
        searchService.searchUsers(q, 1, size, currentUserId, friendIds).getItems();
    List<PostDto> posts = searchService.searchPostsWithBookInfo(q, size, currentUserId, friendIds);
    List<BookDto> books = searchService.searchBooks(q, size, currentUserId, friendIds);

    // B33: projects and roadmaps come from their own domains' read services, not SearchService —
    // they carry no visibility/block filtering (neither does GET /projects or GET /roadmaps), so
    // keeping them out of the class whose every branch does keeps that invariant honest. The term
    // is LIKE-escaped here because those services take it pre-sanitised (see searchProjects).
    String sanitized = SearchQuerySanitizer.sanitize(q);
    List<ProjectResponseDto> projects = projectQueryService.searchProjects(sanitized, size);
    List<RoadmapDto> roadmaps = roadmapService.searchRoadmaps(sanitized, size);

    return new SearchResponse(users, posts, books, projects, roadmaps);
  }

  /**
   * Type-ahead suggestions for the search box, fired per keystroke.
   *
   * <p>Its own endpoint rather than {@code GET /v1/api/search?size=5}: see {@code
   * SuggestService#suggest} for what the results page does that a per-keystroke call must not.
   *
   * <p>Signed-in only, like {@code /search}. Guests read eight endpoints (see {@code
   * SecurityConfig}) and search is deliberately not one of them — an unauthenticated,
   * IP-rate-limited-only endpoint that returns people by partial name is a user-directory dump.
   * Opening it later means adding the path to {@code SecurityConfig} <i>and</i> to {@code
   * GuestRateLimitProperties.paths}; the two lists have to agree or the endpoint is open and
   * unthrottled.
   *
   * <p>{@code limit} is capped at 20 by {@code @Max}. Without a ceiling this is {@code /search}
   * with no pager and no total, which is a cheaper way to page through the user table than the
   * paged endpoint.
   */
  @GetMapping("/suggest")
  public List<SuggestionDto> suggest(
      @RequestParam @NotBlank String q,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_SUGGEST_LIMIT) @Positive @Max(20)
          int limit) {
    return suggestService.suggest(q, limit, SecurityUtils.getCurrentUserId());
  }

  /**
   * The people list behind the {@code @} in a composer.
   *
   * <p>Beside {@link #suggest} rather than inside it, because a mention row is not a search hit:
   * the client inserts the handle into text being typed instead of navigating to a profile, so the
   * handle has to come back as its own non-null field, books have no business in the list, and
   * friends have to be ranked first. See {@code MentionSuggestService} for why that ranking is
   * affordable here and not in the search box.
   *
   * <p>{@code q} is optional, unlike on the two endpoints above. The moment a composer needs this
   * list most is the keystroke right after the {@code @}, when nothing has been typed yet — and
   * that call is answered with the caller's friends rather than with the start of the user table.
   *
   * <p>Signed-in only, like everything else in this controller, and for a sharper reason: this
   * endpoint returns people by partial name with no query at all. Opened to guests it would be a
   * user-directory dump with a blank cursor as the password.
   */
  @GetMapping("/mentions")
  public List<MentionSuggestionDto> suggestMentions(
      @RequestParam(required = false, defaultValue = "") String q,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_SUGGEST_LIMIT) @Positive @Max(20)
          int limit) {
    return mentionSuggestService.suggest(q, limit, SecurityUtils.getCurrentUserId());
  }
}
