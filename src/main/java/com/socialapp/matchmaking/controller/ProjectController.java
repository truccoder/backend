package com.socialapp.matchmaking.controller;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import com.socialapp.matchmaking.dto.ApplicationRequestDTO;
import com.socialapp.matchmaking.dto.ProjectRequestDTO;
import com.socialapp.matchmaking.dto.SuggestedCandidateDto;
import com.socialapp.matchmaking.service.MatchmakingService;
import com.socialapp.matchmaking.service.ProjectService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/projects")
@RequiredArgsConstructor
public class ProjectController {
  private final ProjectService projectService;
  private final MatchmakingService matchmakingService;

  @PostMapping
  public void createProject(@Valid @RequestBody ProjectRequestDTO request) {
    Integer authorId = SecurityUtils.getCurrentUserId();
    projectService.createProject(authorId, request);
  }

  @PostMapping("/positions/{positionId}/apply")
  public void applyToPosition(
      @PathVariable Integer positionId, @Valid @RequestBody ApplicationRequestDTO request) {
    Integer applicantId = SecurityUtils.getCurrentUserId();
    projectService.applyToPosition(applicantId, positionId, request.getMessage());
  }

  @PostMapping("/applications/{applicationId}/accept")
  public void acceptApplication(@PathVariable Integer applicationId) {
    Integer ownerId = SecurityUtils.getCurrentUserId();
    projectService.acceptApplication(ownerId, applicationId);
  }

  @PostMapping("/applications/{applicationId}/reject")
  public void rejectApplication(@PathVariable Integer applicationId) {
    Integer ownerId = SecurityUtils.getCurrentUserId();
    projectService.rejectApplication(ownerId, applicationId);
  }

  @GetMapping("/positions/{positionId}/suggested-candidates")
  public List<SuggestedCandidateDto> getSuggestedCandidates(@PathVariable Integer positionId) {
    return matchmakingService.suggestCandidates(positionId);
  }
}
