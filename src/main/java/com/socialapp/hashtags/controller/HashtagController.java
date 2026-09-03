package com.socialapp.hashtags.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.common.utils.Constants;
import com.socialapp.hashtags.dto.HashtagDto;
import com.socialapp.hashtags.service.HashtagQueryService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/**
 * The read side of {@code t_hashtags} — B31 in {@code docs/backend-plan.md}.
 *
 * <p>Guest-readable, like {@code GET /v1/api/trending} and {@code GET /v1/api/posts/public}: a
 * hashtag list carries no per-user data, and the badges and search box these feed are on pages a
 * visitor can already see. Both paths are additionally IP rate-limited for anonymous callers —
 * {@code GuestRateLimitProperties.paths} has to list them alongside {@code SecurityConfig}.
 *
 * <p>Unlike {@code /v1/api/search/suggest}, which is signed-in only because it completes people's
 * names, nothing here is a directory of users.
 *
 * <p>No {@code @Validated}: the param constraints below are enforced by Spring's built-in
 * controller method validation, which raises {@code HandlerMethodValidationException} → 400. Adding
 * {@code @Validated} would route them through {@code MethodValidationInterceptor} instead, whose
 * {@code ConstraintViolationException} {@code GlobalExceptionHandler} maps to 422 — see {@code
 * TrendingController}, which is deliberately in the same shape.
 */
@RestController
@RequestMapping("/v1/api/hashtags")
@RequiredArgsConstructor
public class HashtagController {

  private final HashtagQueryService hashtagQueryService;

  /**
   * Type-ahead for a {@code #} in the composer or the search box.
   *
   * <p>{@code q} is required and non-blank: this endpoint completes a prefix, and "nothing typed
   * yet" is {@link #trending}'s question, not this one. {@code limit} is capped at 20 — past that
   * this is a paginator over the tag table with no cursor.
   */
  @GetMapping("/suggest")
  public List<HashtagDto> suggest(
      @RequestParam @NotBlank String q,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_SUGGEST_LIMIT) @Positive @Max(20)
          int limit) {
    return hashtagQueryService.suggest(q, limit);
  }

  /**
   * The most-used hashtags inside a time window — the list a search box shows before anything is
   * typed.
   *
   * <p>{@code window} takes the same tokens as {@code GET /v1/api/trending}'s {@code timeRange}
   * ({@code today} / {@code week} / {@code month}); an unknown value falls back to {@code week}
   * rather than 400, matching that endpoint.
   */
  @GetMapping("/trending")
  public List<HashtagDto> trending(
      @RequestParam(defaultValue = "week") String window,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE_SIZE)
          @Positive
          @Max(Constants.MAX_PAGINATION_PAGE_SIZE)
          int limit) {
    return hashtagQueryService.trending(window, limit);
  }
}
