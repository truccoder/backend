package com.socialapp.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.knowledge.dto.ProfessionalProfileResponseDto;
import com.socialapp.knowledge.dto.UpdateProfessionalProfileDto;
import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.entity.enums.PrimaryRole;
import com.socialapp.knowledge.repository.UserProfessionalProfileRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link ProfessionalProfileService}, per ISTQB CTFL v4.0.1 (Section
 * 2.2.1 component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3
 * BDD Given/When/Then) — see {@code PostServiceTest} for the full rationale.
 */
@ExtendWith(MockitoExtension.class)
class ProfessionalProfileServiceTest {

  private static final Integer USER_ID = 1;

  @Mock private UserProfessionalProfileRepository profileRepository;
  @Mock private UserRepository userRepository;

  @InjectMocks private ProfessionalProfileService professionalProfileService;

  private static UpdateProfessionalProfileDto updateDto() {
    UpdateProfessionalProfileDto dto = new UpdateProfessionalProfileDto();
    dto.setJobTitle("Backend Engineer");
    dto.setPrimaryRole(PrimaryRole.BACKEND);
    return dto;
  }

  // =====================================================================
  // getProfile
  // =====================================================================

  @Nested
  @DisplayName("getProfile")
  class GetProfileTests {

    @Test
    @DisplayName("should return the profile when it exists")
    void shouldReturnProfile_whenExists() {
      // Given
      UserProfessionalProfileEntity profile = new UserProfessionalProfileEntity();
      profile.setUserId(USER_ID);
      profile.setJobTitle("Backend Engineer");
      when(profileRepository.findById(USER_ID)).thenReturn(Optional.of(profile));

      // When
      ProfessionalProfileResponseDto result = professionalProfileService.getProfile(USER_ID);

      // Then
      assertThat(result.getUserId()).isEqualTo(USER_ID);
      assertThat(result.getJobTitle()).isEqualTo("Backend Engineer");
    }

    @Test
    @DisplayName("should reject when no profile exists")
    void shouldThrowNotFoundException_whenProfileDoesNotExist() {
      // Given
      when(profileRepository.findById(USER_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> professionalProfileService.getProfile(USER_ID))
          .isInstanceOf(NotFoundException.class);
    }
  }

  // =====================================================================
  // upsertProfile
  // =====================================================================

  @Nested
  @DisplayName("upsertProfile")
  class UpsertProfileTests {

    @Test
    @DisplayName("should reject when the user does not exist")
    void shouldThrowNotFoundException_whenUserDoesNotExist() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> professionalProfileService.upsertProfile(USER_ID, updateDto()))
          .isInstanceOf(NotFoundException.class);
      verify(profileRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("should update the existing profile when present")
    void shouldUpdateExistingProfile_whenPresent() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(new UserEntity()));
      UserProfessionalProfileEntity existing = new UserProfessionalProfileEntity();
      existing.setUserId(USER_ID);
      existing.setJobTitle("Old Title");
      when(profileRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      ProfessionalProfileResponseDto result =
          professionalProfileService.upsertProfile(USER_ID, updateDto());

      // Then
      assertThat(result.getJobTitle()).isEqualTo("Backend Engineer");
      assertThat(result.getUserId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("should create a new profile when none exists")
    void shouldCreateNewProfile_whenNoneExists() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(new UserEntity()));
      when(profileRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      ProfessionalProfileResponseDto result =
          professionalProfileService.upsertProfile(USER_ID, updateDto());

      // Then
      assertThat(result.getUserId()).isEqualTo(USER_ID);
      assertThat(result.getJobTitle()).isEqualTo("Backend Engineer");
    }
  }
}
