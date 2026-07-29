package com.socialapp.knowledge.service;

import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.knowledge.dto.ProfessionalProfileResponseDto;
import com.socialapp.knowledge.dto.UpdateProfessionalProfileDto;
import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.repository.UserProfessionalProfileRepository;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProfessionalProfileService {
  private final UserProfessionalProfileRepository profileRepository;
  private final UserRepository userRepository;

  public ProfessionalProfileResponseDto getProfile(Integer userId) {
    return toDto(
        profileRepository
            .findById(userId)
            .orElseThrow(
                () -> new NotFoundException("Professional profile not found for user: " + userId)));
  }

  @Transactional
  public ProfessionalProfileResponseDto upsertProfile(
      Integer userId, UpdateProfessionalProfileDto dto) {
    userRepository
        .findById(userId)
        .orElseThrow(() -> new NotFoundException("User not found: " + userId));

    UserProfessionalProfileEntity profile =
        profileRepository
            .findById(userId)
            .orElseGet(
                () -> {
                  UserProfessionalProfileEntity newProfile = new UserProfessionalProfileEntity();
                  newProfile.setUserId(userId);
                  return newProfile;
                });

    BeanUtils.copyProperties(dto, profile);
    return toDto(profileRepository.save(profile));
  }

  private ProfessionalProfileResponseDto toDto(UserProfessionalProfileEntity profile) {
    return ProfessionalProfileResponseDto.builder()
        .userId(profile.getUserId())
        .jobTitle(profile.getJobTitle())
        .seniorityLevel(profile.getSeniorityLevel())
        .yearsOfExperience(profile.getYearsOfExperience())
        .primaryRole(profile.getPrimaryRole())
        .explanationStyle(profile.getExplanationStyle())
        .knownTechStack(profile.getKnownTechStack())
        .workHistory(profile.getWorkHistory())
        .interestedDomains(profile.getInterestedDomains())
        .createdAt(profile.getCreatedAt())
        .updatedAt(profile.getUpdatedAt())
        .build();
  }
}
