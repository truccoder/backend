package com.socialapp.roadmap.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.socialapp.roadmap.dto.PendingVerificationDto;
import com.socialapp.roadmap.dto.SkillVerificationRequestDto;
import com.socialapp.roadmap.service.SkillVerificationService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/skills")
@RequiredArgsConstructor
public class SkillVerificationController {

  private final SkillVerificationService skillVerificationService;

  @PostMapping("/verify")
  public void submitVerification(@Valid @RequestBody SkillVerificationRequestDto request) {
    Integer userId = SecurityUtils.getCurrentUserId();
    skillVerificationService.submitVerificationRequest(userId, request);
  }

  @GetMapping("/pending")
  @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
  public List<PendingVerificationDto> getPendingRequests() {
    return skillVerificationService.getPendingRequests();
  }

  @PostMapping("/{progressId}/approve")
  @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
  public void approveRequest(@PathVariable Integer progressId) {
    Integer modId = SecurityUtils.getCurrentUserId();
    skillVerificationService.approveRequest(progressId, modId);
  }

  @PostMapping("/{progressId}/reject")
  @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
  public void rejectRequest(@PathVariable Integer progressId) {
    Integer modId = SecurityUtils.getCurrentUserId();
    skillVerificationService.rejectRequest(progressId, modId);
  }
}
