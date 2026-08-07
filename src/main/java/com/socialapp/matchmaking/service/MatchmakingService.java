package com.socialapp.matchmaking.service;

import java.util.List;

import org.springframework.stereotype.Service;

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

  public List<SuggestedCandidateDto> suggestCandidates(Integer positionId) {
    ProjectPositionEntity position =
        positionRepository
            .findById(positionId)
            .orElseThrow(() -> new NotFoundException("Position not found"));

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
