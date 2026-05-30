package com.socialapp.moderation.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.moderation.dto.AdminReviewRequestDto;
import com.socialapp.moderation.dto.PendingReviewPostDto;
import com.socialapp.moderation.service.AdminModerationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/admin/moderation")
@RequiredArgsConstructor
public class AdminModerationController {
  private final AdminModerationService adminModerationService;

  @GetMapping("/pending")
  public List<PendingReviewPostDto> getPendingReviewPosts() {
    return adminModerationService.getPendingReviewPosts();
  }

  @PostMapping("/posts/{postId}/review")
  public void reviewPost(
      @PathVariable Integer postId, @Valid @RequestBody AdminReviewRequestDto request) {
    adminModerationService.reviewPost(postId, request.getDecision(), request.getFeedback());
  }
}
