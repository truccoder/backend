package com.socialapp.github.controller;

import org.springframework.web.bind.annotation.*;

import com.socialapp.github.dto.GithubLinkRequest;
import com.socialapp.github.dto.GithubOAuthUrlResponse;
import com.socialapp.github.dto.GithubStatsResponse;
import com.socialapp.github.service.GithubService;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/github")
@RequiredArgsConstructor
public class GithubController {

  private final GithubService githubService;

  @GetMapping("/oauth/url")
  public GithubOAuthUrlResponse getOAuthUrl() {
    return githubService.getOAuthUrl();
  }

  @PostMapping("/oauth/callback")
  public void handleOAuthCallback(@Valid @RequestBody GithubLinkRequest request) {
    UserEntity currentUser = SecurityUtils.requireCurrentUser();
    githubService.linkAccountWithCode(currentUser, request.getCode());
  }

  @DeleteMapping("/unlink")
  public void unlinkAccount() {
    UserEntity currentUser = SecurityUtils.requireCurrentUser();
    githubService.unlinkAccount(currentUser);
  }

  /** 404s via {@code NotFoundException} when the user has no linked GitHub account. */
  @GetMapping("/stats/{userId}")
  public GithubStatsResponse getStats(@PathVariable Integer userId) {
    return githubService.getGithubStats(userId);
  }

  @PostMapping("/sync")
  public void syncNow() {
    UserEntity currentUser = SecurityUtils.requireCurrentUser();
    githubService.syncNow(currentUser.getId());
  }
}
