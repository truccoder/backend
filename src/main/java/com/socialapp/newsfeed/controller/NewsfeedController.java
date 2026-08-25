package com.socialapp.newsfeed.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.common.utils.Constants;
import com.socialapp.newsfeed.dto.FeedResponseDto;
import com.socialapp.newsfeed.dto.FeedScope;
import com.socialapp.newsfeed.service.NewsfeedService;
import com.socialapp.security.util.SecurityUtils;

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
}
