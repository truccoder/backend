package com.socialapp.newsfeed.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.common.utils.Constants;
import com.socialapp.newsfeed.dto.FeedResponseDto;
import com.socialapp.newsfeed.dto.FeedScope;
import com.socialapp.newsfeed.dto.MarkSeenRequestDto;
import com.socialapp.newsfeed.service.NewsfeedService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/feed")
@RequiredArgsConstructor
public class NewsfeedController {
  private final NewsfeedService newsfeedService;

  /**
   * @param scope {@code ALL} (the default, and what this endpoint has always returned) or {@code
   *     SKILLS}, which keeps only the posts touching a skill the caller has had verified. It
   *     narrows the caller's own feed — it is not a topic search across all posts; see {@code
   *     NewsfeedService.getFeed}.
   */
  @GetMapping
  public FeedResponseDto getFeed(
      @RequestParam(defaultValue = "ALL") FeedScope scope,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE) @Positive int page,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE_SIZE)
          @Positive
          @Max(Constants.MAX_PAGINATION_PAGE_SIZE)
          int size) {
    return newsfeedService.getFeed(SecurityUtils.getCurrentUserId(), page, size, scope);
  }

  /**
   * Reports the posts the caller has scrolled past, so the feed stops putting them back on top.
   *
   * <p>204 rather than 200: there is nothing to send back, and the client must not be waiting on this
   * before it renders anything. It is a beacon fired while scrolling, not a step in a workflow.
   *
   * <p>Whose posts these are is never taken from the body — the seen list is keyed by the token's
   * user id, so the worst a caller can do with a forged id is reorder their own feed.
   *
   * <p>Three failure modes worth knowing apart, and they differ from the {@code GET} above: a body
   * that breaks {@code @Valid} is <b>422</b>, malformed JSON is <b>400</b>, and a missing or wrong
   * {@code Content-Type} is <b>415</b> — see {@code GlobalExceptionHandler}'s class Javadoc for why
   * the first two are not the same status.
   */
  @PostMapping("/seen")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markSeen(@Valid @RequestBody MarkSeenRequestDto request) {
    newsfeedService.markSeen(SecurityUtils.getCurrentUserId(), request.getPostIds());
  }
}
