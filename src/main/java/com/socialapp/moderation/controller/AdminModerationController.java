package com.socialapp.moderation.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import com.socialapp.common.utils.Constants;
import com.socialapp.moderation.dto.AdminReviewRequestDto;
import com.socialapp.moderation.dto.AppealDecisionRequestDto;
import com.socialapp.moderation.dto.AppealDto;
import com.socialapp.moderation.dto.BannedUserDto;
import com.socialapp.moderation.dto.ModerationLogDto;
import com.socialapp.moderation.dto.PostModerationDetailDto;
import com.socialapp.moderation.enums.AppealStatus;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.service.AdminModerationService;
import com.socialapp.moderation.service.AppealService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/admin/moderation")
@RequiredArgsConstructor
public class AdminModerationController {
  private final AdminModerationService adminModerationService;
  private final AppealService appealService;

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
    adminModerationService.reviewPost(
        postId, request.getDecision(), request.getViolationType(), request.getFeedback());
  }

  /**
   * The appeal queue, oldest first.
   *
   * <p>Defaults to {@code PENDING} because that is the only status with anything to do; the
   * parameter exists so a decided appeal can still be looked up.
   */
  @GetMapping("/appeals")
  public Page<AppealDto> getAppeals(
      @RequestParam(defaultValue = "PENDING") AppealStatus status,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE) @Positive int page,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE_SIZE) @Positive int size) {
    return appealService.getAppeals(status, PageRequest.of(page - 1, size));
  }

  /** Upholds an appeal: the violation is erased and the ban re-evaluated. */
  @PostMapping("/appeals/{appealId}/approve")
  public AppealDto approveAppeal(
      @PathVariable Long appealId,
      @Valid @RequestBody(required = false) AppealDecisionRequestDto request) {
    return appealService.approve(appealId, SecurityUtils.getCurrentUserId(), noteOf(request));
  }

  /** Lets the sanction stand. */
  @PostMapping("/appeals/{appealId}/reject")
  public AppealDto rejectAppeal(
      @PathVariable Long appealId,
      @Valid @RequestBody(required = false) AppealDecisionRequestDto request) {
    return appealService.reject(appealId, SecurityUtils.getCurrentUserId(), noteOf(request));
  }

  // The note is optional, and so is the body carrying it — an admin who just wants to reject
  // should not have to POST "{}".
  private String noteOf(AppealDecisionRequestDto request) {
    return request == null ? null : request.getReviewerNote();
  }
}
