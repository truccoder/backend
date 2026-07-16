package com.socialapp.github.controller;

import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/github")
@RequiredArgsConstructor
public class GithubController {

  private final GithubService githubService;

  @GetMapping("/oauth/url")
  public ResponseEntity<GithubOAuthUrlResponse> getOAuthUrl() {
    return ResponseEntity.ok(githubService.getOAuthUrl());
  }

  @PostMapping("/oauth/callback")
  public ResponseEntity<Void> handleOAuthCallback(@Valid @RequestBody GithubLinkRequest request) {
    UserEntity currentUser = SecurityUtils.requireCurrentUser();
    githubService.linkAccountWithCode(currentUser, request.getCode());
    return ResponseEntity.ok().build();
  }

  @DeleteMapping("/unlink")
  public ResponseEntity<Void> unlinkAccount() {
    UserEntity currentUser = SecurityUtils.requireCurrentUser();
    githubService.unlinkAccount(currentUser);
    return ResponseEntity.ok().build();
  }

  @GetMapping("/stats/{userId}")
  public ResponseEntity<GithubStatsResponse> getStats(@PathVariable Integer userId) {
    GithubStatsResponse stats = githubService.getGithubStats(userId);
    if (stats == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(stats);
  }

  @PostMapping("/sync")
  public ResponseEntity<Void> syncNow() {
    UserEntity currentUser = SecurityUtils.requireCurrentUser();
    githubService.syncNow(currentUser.getId());
    return ResponseEntity.ok().build();
  }
}
