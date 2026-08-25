package com.socialapp.matchmaking.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.repository.UserProfessionalProfileRepository;
import com.socialapp.matchmaking.dto.SuggestedCandidateDto;
import com.socialapp.matchmaking.entity.ProjectPositionEntity;
import com.socialapp.matchmaking.repository.ProjectPositionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MatchmakingService {
  private final UserProfessionalProfileRepository profileRepository;
  private final ProjectPositionRepository positionRepository;

  /**
   * Who might fill this role, for the project owner to approach.
   *
   * <p><b>Owner-only.</b> The reply carries other users' job title, seniority, years of experience
   * and tech stack, which is a professional profile in all but name. This took only a position id
   * and answered any signed-in caller, so any of them could walk position ids and harvest the
   * directory. Its sibling {@code ProjectQueryService.getApplicationsForProject} already took the
   * caller and threw {@link ForbiddenException}; this now matches it.
   *
   * <p>{@code @Transactional} because the ownership check walks two LAZY associations
   * ({@code position.project.author}) and {@code open-in-view} is off.
   */
  @Transactional(readOnly = true)
  public List<SuggestedCandidateDto> suggestCandidates(Integer positionId, Integer callerId) {
    ProjectPositionEntity position =
        positionRepository
            .findById(positionId)
            .orElseThrow(() -> new NotFoundException("Position not found"));

    if (!position.getProject().getAuthor().getId().equals(callerId)) {
      throw new ForbiddenException("Not authorized to view candidates for this position");
    }

    List<String> skills = position.getRequiredSkills();
    if (skills == null || skills.isEmpty()) {
      return List.of();
    }

    return profileRepository.findBySkillsMatch(skills).stream().map(this::toCandidateDto).toList();
  }

  private SuggestedCandidateDto toCandidateDto(UserProfessionalProfileEntity profile) {
    return SuggestedCandidateDto.builder()
        .userId(profile.getUserId())
        .jobTitle(profile.getJobTitle())
        .seniorityLevel(profile.getSeniorityLevel())
        .yearsOfExperience(profile.getYearsOfExperience())
        .primaryRole(profile.getPrimaryRole())
        .knownTechStack(profile.getKnownTechStack())
        .build();
  }
}
