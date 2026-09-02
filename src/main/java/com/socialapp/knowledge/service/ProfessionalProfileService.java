package com.socialapp.knowledge.service;

import java.util.stream.Stream;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
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

    // Only the fields the request actually mentions. A bare copyProperties wrote null over every
    // column the caller left out, so a client sending just {"seniorityLevel":"SENIOR"} — valid,
    // since that is the DTO's only @NotNull — silently erased jobTitle, primaryRole,
    // explanationStyle, knownTechStack, workHistory and interestedDomains. Same fix and same
    // reasoning as PostService's update path.
    BeanUtils.copyProperties(dto, profile, nullPropertyNames(dto));
    return toDto(profileRepository.save(profile));
  }

  /**
   * The property names on {@code source} that are currently null, for {@code copyProperties} to
   * skip. Null means "not supplied" on this endpoint — there is no way to clear a field to null
   * through it, which is the same bargain every other partial-update path in the app makes.
   */
  private static String[] nullPropertyNames(Object source) {
    BeanWrapper wrapped = new BeanWrapperImpl(source);
    return Stream.of(wrapped.getPropertyDescriptors())
        .map(java.beans.PropertyDescriptor::getName)
        .filter(name -> wrapped.getPropertyValue(name) == null)
        .toArray(String[]::new);
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
