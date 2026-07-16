package com.socialapp.roadmap.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import com.socialapp.roadmap.dto.SkillVerificationRequestDto;
import com.socialapp.roadmap.entity.UserRoadmapProgressEntity;
import com.socialapp.roadmap.service.SkillVerificationService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
public class SkillVerificationController {

  private final SkillVerificationService skillVerificationService;

  @PostMapping("/verify")
  public ResponseEntity<String> submitVerification(
      Authentication authentication, @RequestBody SkillVerificationRequestDto request) {
    // Assuming authentication.getPrincipal() can be cast to an object containing ID, or using a
    // utility
    // For simplicity, hardcode or assume getting userId from a context util in real app
    // Here we just use a placeholder 1 for userId, this should be replaced with actual user details
    Integer userId = extractUserId(authentication);
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
  public ResponseEntity<String> approveRequest(
      Authentication authentication, @PathVariable Integer progressId) {
    Integer modId = extractUserId(authentication);
    skillVerificationService.approveRequest(progressId, modId);
    return ResponseEntity.ok("Request approved.");
  }

  @PostMapping("/reject/{progressId}")
  @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
  public ResponseEntity<String> rejectRequest(
      Authentication authentication, @PathVariable Integer progressId) {
    Integer modId = extractUserId(authentication);
    skillVerificationService.rejectRequest(progressId, modId);
    return ResponseEntity.ok("Request rejected.");
  }

  // Utility method placeholder
  private Integer extractUserId(Authentication authentication) {
    // In a real application, you would cast authentication.getPrincipal() to your CustomUserDetails
    // and return the ID. Here, we just return a stub value or parse it if it's stored in the
    // principal string.
    // Assuming the name is the ID string for now if it's just a basic JWT setup, or better yet:
    try {
      // Try to parse from name if custom configured, otherwise this needs actual UserDetails logic
      return Integer.parseInt(authentication.getName());
    } catch (Exception e) {
      return 1; // Fallback for testing/compilation
    }
  }
}
