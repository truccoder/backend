package com.socialapp.newsfeed.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.common.utils.Constants;
import com.socialapp.newsfeed.dto.FeedResponseDto;
import com.socialapp.newsfeed.service.NewsfeedService;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/feed")
@RequiredArgsConstructor
public class NewsfeedController {
  private final NewsfeedService newsfeedService;

  @GetMapping
  public FeedResponseDto getFeed(
      @RequestHeader("X-User-Id") Integer userId,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE) @Positive int page,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE_SIZE) @Positive int size) {
    return newsfeedService.getFeed(userId, page, size);
  }
}
