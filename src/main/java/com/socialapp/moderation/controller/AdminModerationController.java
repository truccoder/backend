package com.socialapp.moderation.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import com.socialapp.common.utils.Constants;
import com.socialapp.moderation.dto.AdminReviewRequestDto;
import com.socialapp.moderation.dto.BannedUserDto;
import com.socialapp.moderation.dto.ModerationLogDto;
import com.socialapp.moderation.dto.PostModerationDetailDto;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.service.AdminModerationService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/admin/moderation")
@RequiredArgsConstructor
public class AdminModerationController {
  private final AdminModerationService adminModerationService;

  @GetMapping("/posts")
  public Page<PostModerationDetailDto> searchPosts(
      @RequestParam(required = false) Integer postId,
      @RequestParam(required = false) Integer userId,
      @RequestParam(required = false) ModerationStatus status,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE) @Positive int page,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE_SIZE) @Positive int size) {
    return adminModerationService.searchPosts(
        postId, userId, status, PageRequest.of(page - 1, size));
  }

  @GetMapping("/logs")
  public Page<ModerationLogDto> searchLogs(
      @RequestParam(required = false) Integer postId,
      @RequestParam(required = false) Integer userId,
      @RequestParam(required = false) ModerationStatus status,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE) @Positive int page,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE_SIZE) @Positive int size) {
    return adminModerationService.searchLogs(
        postId, userId, status, PageRequest.of(page - 1, size));
  }

  @GetMapping("/banned-users")
  public Page<BannedUserDto> getBannedUsers(
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE) @Positive int page,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE_SIZE) @Positive int size) {
    return adminModerationService.getBannedUsers(PageRequest.of(page - 1, size));
  }

  @PostMapping("/posts/{postId}/review")
  public void reviewPost(
      @PathVariable Integer postId, @Valid @RequestBody AdminReviewRequestDto request) {
    adminModerationService.reviewPost(postId, request.getDecision(), request.getFeedback());
  }
}
