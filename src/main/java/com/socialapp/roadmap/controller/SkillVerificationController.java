package com.socialapp.roadmap.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.socialapp.roadmap.dto.SkillVerificationRequestDto;
import com.socialapp.roadmap.entity.UserRoadmapProgressEntity;
import com.socialapp.roadmap.service.SkillVerificationService;
import com.socialapp.security.util.SecurityUtils;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
public class SkillVerificationController {

  private final SkillVerificationService skillVerificationService;

  @PostMapping("/verify")
  public ResponseEntity<String> submitVerification(
      @RequestBody SkillVerificationRequestDto request) {
    Integer userId = SecurityUtils.getCurrentUserId();
    skillVerificationService.submitVerificationRequest(userId, request);
    return ResponseEntity.ok("Verification request submitted successfully.");
  }

  @GetMapping("/pending")
  @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
  public ResponseEntity<List<UserRoadmapProgressEntity>> getPendingRequests() {
    return ResponseEntity.ok(skillVerificationService.getPendingRequests());
  }

  @PostMapping("/approve/{progressId}")
  @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
  public ResponseEntity<String> approveRequest(@PathVariable Integer progressId) {
    Integer modId = SecurityUtils.getCurrentUserId();
    skillVerificationService.approveRequest(progressId, modId);
    return ResponseEntity.ok("Request approved.");
  }

  @PostMapping("/reject/{progressId}")
  @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
  public ResponseEntity<String> rejectRequest(@PathVariable Integer progressId) {
    Integer modId = SecurityUtils.getCurrentUserId();
    skillVerificationService.rejectRequest(progressId, modId);
    return ResponseEntity.ok("Request rejected.");
  }
}
