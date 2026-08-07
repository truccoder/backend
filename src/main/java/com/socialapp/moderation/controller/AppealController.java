package com.socialapp.moderation.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.moderation.dto.AppealDto;
import com.socialapp.moderation.dto.AppealRequestDto;
import com.socialapp.moderation.dto.UserViolationDto;
import com.socialapp.moderation.service.AppealService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * The sanctioned user's side of moderation: see what was recorded, dispute it, follow the outcome.
 *
 * <p>Every endpoint here is reachable while banned — see {@code
 * JwtAuthenticationFilter#isBannedUserAllowed}. That exemption and these paths are one feature: an
 * appeal endpoint a banned user cannot call is decoration, and an outcome they cannot read is the
 * same dead end one step later.
 */
@RestController
@RequestMapping("/v1/api/moderation")
@RequiredArgsConstructor
public class AppealController {

  private final AppealService appealService;

  /** What has been recorded against the caller — the list they pick from to appeal. */
  @GetMapping("/my-violations")
  public List<UserViolationDto> getMyViolations() {
    return appealService.getMyViolations(SecurityUtils.getCurrentUserId());
  }

  @PostMapping("/appeals")
  @ResponseStatus(HttpStatus.CREATED)
  public AppealDto submitAppeal(@Valid @RequestBody AppealRequestDto request) {
    return appealService.submitAppeal(SecurityUtils.getCurrentUserId(), request);
  }

  /** The caller's own appeals and how each one was decided. */
  @GetMapping("/appeals")
  public List<AppealDto> getMyAppeals() {
    return appealService.getMyAppeals(SecurityUtils.getCurrentUserId());
  }
}
