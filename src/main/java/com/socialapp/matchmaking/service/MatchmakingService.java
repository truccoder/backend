package com.socialapp.matchmaking.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.repository.UserProfessionalProfileRepository;
import com.socialapp.matchmaking.entity.ProjectPositionEntity;
import com.socialapp.matchmaking.repository.ProjectPositionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MatchmakingService {

  private final UserProfessionalProfileRepository profileRepository;
  private final ProjectPositionRepository positionRepository;

  public List<UserProfessionalProfileEntity> suggestCandidates(Integer positionId) {
    ProjectPositionEntity position =
        positionRepository
            .findById(positionId)
            .orElseThrow(() -> new RuntimeException("Position not found"));

    List<String> skills = position.getRequiredSkills();
    if (skills == null || skills.isEmpty()) {
      return List.of();
    }

    return profileRepository.findBySkillsMatch(skills);
  }
}
