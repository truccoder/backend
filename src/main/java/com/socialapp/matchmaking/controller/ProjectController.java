package com.socialapp.matchmaking.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.socialapp.matchmaking.dto.ApplicationRequestDTO;
import com.socialapp.matchmaking.dto.ProjectRequestDTO;
import com.socialapp.matchmaking.service.MatchmakingService;
import com.socialapp.matchmaking.service.ProjectService;
import com.socialapp.security.util.SecurityUtils;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {
  private final ProjectService projectService;
  private final MatchmakingService matchmakingService;

  @PostMapping
  public ResponseEntity<?> createProject(@RequestBody ProjectRequestDTO request) {
    Integer authorId = SecurityUtils.getCurrentUserId();
    if (authorId == null) {
      authorId = 1; // Fallback for dev if needed
    }
    projectService.createProject(authorId, request);
    return ResponseEntity.ok(Map.of("message", "Project created successfully"));
  }

  @PostMapping("/positions/{positionId}/apply")
  public ResponseEntity<?> applyToPosition(
      @PathVariable Integer positionId, @RequestBody ApplicationRequestDTO request) {
    Integer applicantId = SecurityUtils.getCurrentUserId();
    if (applicantId == null) {
      applicantId = 2; // Fallback for dev if needed
    }
    projectService.applyToPosition(applicantId, positionId, request.getMessage());
    return ResponseEntity.ok(Map.of("message", "Applied successfully"));
  }

  @PutMapping("/applications/{applicationId}/accept")
  public ResponseEntity<?> acceptApplication(@PathVariable Integer applicationId) {
    Integer ownerId = SecurityUtils.getCurrentUserId();
    if (ownerId == null) {
      ownerId = 1; // Fallback for dev if needed
    }
    projectService.acceptApplication(ownerId, applicationId);
    return ResponseEntity.ok(Map.of("message", "Application accepted"));
  }

  @GetMapping("/positions/{positionId}/suggested-candidates")
  public ResponseEntity<?> getSuggestedCandidates(@PathVariable Integer positionId) {
    // Returning entities directly might expose some internal structure,
    // but for this MVP, it gives the necessary data.
    return ResponseEntity.ok(matchmakingService.suggestCandidates(positionId));
  }
}
