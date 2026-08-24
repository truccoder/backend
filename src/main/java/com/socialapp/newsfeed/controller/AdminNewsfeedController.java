package com.socialapp.newsfeed.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.newsfeed.dto.FeedRebuildResultDto;
import com.socialapp.newsfeed.service.NewsfeedService;

import lombok.RequiredArgsConstructor;

/**
 * Feed maintenance, for administrators.
 *
 * <p>Under {@code /v1/api/admin/**}, which {@code SecurityConfig} pins to {@code hasRole("ADMIN")}
 * — the same rule that guards the moderation console, and the reason there is no {@code
 * @PreAuthorize} repeated here.
 */
@RestController
@RequestMapping("/v1/api/admin/newsfeed")
@RequiredArgsConstructor
public class AdminNewsfeedController {
  private final NewsfeedService newsfeedService;

  /**
   * Rebuilds every user's feed from Postgres — see {@code NewsfeedService#rebuildAll}.
   *
   * <p>Run it after seeding a database (seeded posts never pass through the publish path, so no
   * feed contains them) and after any loss of Redis.
   */
  @PostMapping("/rebuild")
  public FeedRebuildResultDto rebuild() {
    return newsfeedService.rebuildAll();
  }
}
