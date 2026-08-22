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

/**
 * Claiming a skill, and the moderator side that rules on the claim.
 *
 * <p><b>{@code hasRole('ADMIN')}, not {@code hasAnyRole('ADMIN', 'MODERATOR')}.</b> These three
 * guards used to name a moderator role that {@link com.socialapp.security.entity.UserRole} does not
 * have — its members are {@code USER} and {@code ADMIN} — so the extra branch could never match
 * anybody and only suggested a moderator tier that does not exist. There is no way to grant such a
 * role today: no column value, no endpoint, no admin screen. Naming it here made the rule read as
 * broader than it is. The frontend's {@code useIsRoadmapAdmin} already gates on {@code ADMIN}
 * alone; this now agrees with it.
 *
 * <p>The guards are duplicated as path rules in {@code SecurityConfig} — see the note there on why
 * an annotation is not trusted as the only control on this surface.
 */
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
  @PreAuthorize("hasRole('ADMIN')")
  public List<PendingVerificationDto> getPendingRequests() {
    return skillVerificationService.getPendingRequests();
  }

  @PostMapping("/{progressId}/approve")
  @PreAuthorize("hasRole('ADMIN')")
  public void approveRequest(@PathVariable Integer progressId) {
    Integer modId = SecurityUtils.getCurrentUserId();
    skillVerificationService.approveRequest(progressId, modId);
  }

  @PostMapping("/{progressId}/reject")
  @PreAuthorize("hasRole('ADMIN')")
  public void rejectRequest(@PathVariable Integer progressId) {
    Integer modId = SecurityUtils.getCurrentUserId();
    skillVerificationService.rejectRequest(progressId, modId);
  }
}
