package com.socialapp.matchmaking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.repository.UserProfessionalProfileRepository;
import com.socialapp.matchmaking.dto.SuggestedCandidateDto;
import com.socialapp.matchmaking.entity.ProjectPositionEntity;
import com.socialapp.matchmaking.repository.ProjectPositionRepository;

/**
 * Component (unit) tests for {@link MatchmakingService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing; Section 4.2.1 equivalence partitioning + Section 4.3.2 branch testing over
 * the null / empty / non-empty required-skills partitions of {@code suggestCandidates}).
 */
@ExtendWith(MockitoExtension.class)
class MatchmakingServiceTest {

  private static final Integer POSITION_ID = 1;

  @Mock private UserProfessionalProfileRepository profileRepository;
  @Mock private ProjectPositionRepository positionRepository;

  @InjectMocks private MatchmakingService matchmakingService;

  private static ProjectPositionEntity position(List<String> requiredSkills) {
    ProjectPositionEntity position = new ProjectPositionEntity();
    position.setId(POSITION_ID);
    position.setRequiredSkills(requiredSkills);
    return position;
  }

  @Nested
  @DisplayName("suggestCandidates")
  class SuggestCandidatesTests {

    @Test
    @DisplayName("should reject when the position does not exist")
    void shouldThrowNotFoundException_whenPositionDoesNotExist() {
      // Given
      when(positionRepository.findById(POSITION_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> matchmakingService.suggestCandidates(POSITION_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should return no candidates when the position has no required skills list")
    void shouldReturnEmptyList_whenRequiredSkillsIsNull() {
      // Given
      when(positionRepository.findById(POSITION_ID)).thenReturn(Optional.of(position(null)));

      // When
      List<SuggestedCandidateDto> result = matchmakingService.suggestCandidates(POSITION_ID);

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should return no candidates when the required skills list is empty")
    void shouldReturnEmptyList_whenRequiredSkillsIsEmpty() {
      // Given
      when(positionRepository.findById(POSITION_ID)).thenReturn(Optional.of(position(List.of())));

      // When
      List<SuggestedCandidateDto> result = matchmakingService.suggestCandidates(POSITION_ID);

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should delegate to the profile repository when required skills are present")
    void shouldDelegateToProfileRepository_whenRequiredSkillsPresent() {
      // Given
      List<String> skills = List.of("Java", "Spring");
      when(positionRepository.findById(POSITION_ID)).thenReturn(Optional.of(position(skills)));
      UserProfessionalProfileEntity candidate = new UserProfessionalProfileEntity();
      candidate.setUserId(77);
      candidate.setJobTitle("Backend Engineer");
      when(profileRepository.findBySkillsMatch(skills)).thenReturn(List.of(candidate));

      // When
      List<SuggestedCandidateDto> result = matchmakingService.suggestCandidates(POSITION_ID);

      // Then
      assertThat(result)
          .singleElement()
          .satisfies(
              dto -> {
                assertThat(dto.getUserId()).isEqualTo(77);
                assertThat(dto.getJobTitle()).isEqualTo("Backend Engineer");
              });
    }
  }
}
