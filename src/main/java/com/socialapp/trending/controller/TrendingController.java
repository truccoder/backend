package com.socialapp.trending.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.common.utils.Constants;
import com.socialapp.trending.dto.TrendingPageResponseDto;
import com.socialapp.trending.entity.enums.TrendingCategory;
import com.socialapp.trending.entity.enums.TrendingSource;
import com.socialapp.trending.service.TrendingService;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/trending")
@RequiredArgsConstructor
public class TrendingController {
  private final TrendingService trendingService;

  /**
   * @param source narrows the list to one crawler. Absent means all three, interleaved by
   *     per-source percent rank rather than by raw score — the source label on each card tells a
   *     reader where a row came from, and this parameter is what lets them ask for only that.
   */
  @GetMapping
  public TrendingPageResponseDto getTrending(
      @RequestParam(required = false) TrendingCategory category,
      @RequestParam(required = false) TrendingSource source,
      @RequestParam(defaultValue = "week") String timeRange,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE) @Positive int page,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE_SIZE) @Positive int size) {
    return trendingService.getTrending(category, source, timeRange, page, size);
  }
}
