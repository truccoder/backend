package com.socialapp.trending.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.common.utils.Constants;
import com.socialapp.trending.dto.TrendingPageResponseDto;
import com.socialapp.trending.entity.enums.TrendingCategory;
import com.socialapp.trending.service.TrendingService;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/trending")
@RequiredArgsConstructor
public class TrendingController {
  private final TrendingService trendingService;

  @GetMapping
  public TrendingPageResponseDto getTrending(
      @RequestParam(required = false) TrendingCategory category,
      @RequestParam(defaultValue = "week") String timeRange,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE) @Positive int page,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE_SIZE) @Positive int size) {
    return trendingService.getTrending(category, timeRange, page, size);
  }
}
