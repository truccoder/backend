package com.socialapp.reputation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
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
import com.socialapp.reputation.RepLevel;
import com.socialapp.reputation.dto.ReputationResponseDto;
import com.socialapp.reputation.entity.enums.RepSourceType;
import com.socialapp.reputation.repository.ReputationEventRepository;
import com.socialapp.roadmap.enums.VerificationStatus;
import com.socialapp.roadmap.repository.UserRoadmapProgressRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link ReputationService}, per ISTQB CTFL v4.0.1 Section 2.2.1
 * (component testing) — collaborators mocked with Mockito. BDD Given/When/Then per Section 2.1.3.
 * Idempotency (Section 4.3: the DB unique constraint, not a check-then-act read, is what makes
 * re-awarding a no-op) is asserted via the repository's returned row count, since the constraint
 * itself only proves out under Testcontainers/repository-level tests.
 */
@ExtendWith(MockitoExtension.class)
class ReputationServiceTest {

  private static final Integer USER_ID = 1;
  private static final String SOURCE_ID = "100:2";

  @Mock private ReputationEventRepository reputationEventRepository;
  @Mock private UserRepository userRepository;
  @Mock private UserRoadmapProgressRepository userRoadmapProgressRepository;

  @InjectMocks private ReputationService reputationService;

  // =====================================================================
  // award
  // =====================================================================

  @Nested
  @DisplayName("award")
  class AwardTests {

    @Test
    @DisplayName("should bump the user's elite score when the event is newly recorded")
    void shouldBumpEliteScore_whenEventIsNewlyRecorded() {
      // Given
      when(reputationEventRepository.insertIfAbsent(
              USER_ID,
              RepSourceType.REACTION_RECEIVED.name(),
              SOURCE_ID,
              RepSourceType.REACTION_RECEIVED.getPoints()))
          .thenReturn(1);

      // When
      reputationService.award(USER_ID, RepSourceType.REACTION_RECEIVED, SOURCE_ID);

      // Then
      verify(userRepository).adjustEliteScore(USER_ID, RepSourceType.REACTION_RECEIVED.getPoints());
    }

    @Test
    @DisplayName("should not bump the elite score when the event already exists (idempotent)")
    void shouldNotBumpEliteScore_whenEventAlreadyExists() {
      // Given
      when(reputationEventRepository.insertIfAbsent(
              USER_ID,
              RepSourceType.ACCEPTED_ANSWER.name(),
              SOURCE_ID,
              RepSourceType.ACCEPTED_ANSWER.getPoints()))
          .thenReturn(0);

      // When
      reputationService.award(USER_ID, RepSourceType.ACCEPTED_ANSWER, SOURCE_ID);

      // Then
      verify(userRepository, never()).adjustEliteScore(anyInt(), anyInt());
    }
  }

  // =====================================================================
  // revoke
  // =====================================================================

  @Nested
  @DisplayName("revoke")
  class RevokeTests {

    @Test
    @DisplayName("should decrement the elite score when a matching event is deleted")
    void shouldDecrementEliteScore_whenMatchingEventDeleted() {
      // Given
      when(reputationEventRepository.deleteByUserIdAndSourceTypeAndSourceId(
              USER_ID, RepSourceType.REACTION_RECEIVED, SOURCE_ID))
          .thenReturn(1);

      // When
      reputationService.revoke(USER_ID, RepSourceType.REACTION_RECEIVED, SOURCE_ID);

      // Then
      verify(userRepository)
          .adjustEliteScore(USER_ID, -RepSourceType.REACTION_RECEIVED.getPoints());
    }

    @Test
    @DisplayName("should do nothing when there is no matching event to revoke")
    void shouldDoNothing_whenNoMatchingEventExists() {
      // Given
      when(reputationEventRepository.deleteByUserIdAndSourceTypeAndSourceId(
              USER_ID, RepSourceType.REACTION_RECEIVED, SOURCE_ID))
          .thenReturn(0);

      // When
      reputationService.revoke(USER_ID, RepSourceType.REACTION_RECEIVED, SOURCE_ID);

      // Then
      verify(userRepository, never()).adjustEliteScore(anyInt(), anyInt());
    }
  }

  // =====================================================================
  // getReputation
  // =====================================================================

  @Nested
  @DisplayName("getReputation")
  class GetReputationTests {

    @Test
    @DisplayName("should report the level, next threshold, and verified-expert badge")
    void shouldReportLevelNextThresholdAndBadge() {
      // Given
      UserEntity user = new UserEntity();
      user.setId(USER_ID);
      user.setEliteScore(120);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(userRoadmapProgressRepository.existsByUserIdAndStatus(
              USER_ID, VerificationStatus.VERIFIED))
          .thenReturn(true);

      // When
      ReputationResponseDto result = reputationService.getReputation(USER_ID);

      // Then
      assertThat(result.getEliteScore()).isEqualTo(120);
      assertThat(result.getLevel()).isEqualTo(RepLevel.CONTRIBUTOR.getLevel());
      assertThat(result.getLevelName()).isEqualTo("Contributor");
      assertThat(result.getNextLevelMin()).isEqualTo(RepLevel.PRACTITIONER.getMin());
      assertThat(result.isVerifiedExpert()).isTrue();
    }

    @Test
    @DisplayName("should report a null next-level threshold once Elite is reached")
    void shouldReportNullNextThreshold_whenEliteReached() {
      // Given
      UserEntity user = new UserEntity();
      user.setId(USER_ID);
      user.setEliteScore(60_000);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(userRoadmapProgressRepository.existsByUserIdAndStatus(
              USER_ID, VerificationStatus.VERIFIED))
          .thenReturn(false);

      // When
      ReputationResponseDto result = reputationService.getReputation(USER_ID);

      // Then
      assertThat(result.getLevelName()).isEqualTo("Elite");
      assertThat(result.getNextLevelMin()).isNull();
      assertThat(result.isVerifiedExpert()).isFalse();
    }

    @Test
    @DisplayName("should throw NotFoundException when the user does not exist")
    void shouldThrowNotFoundException_whenUserDoesNotExist() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> reputationService.getReputation(USER_ID))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("User not found");
    }
  }

  // =====================================================================
  // reconcile
  // =====================================================================

  @Nested
  @DisplayName("reconcile")
  class ReconcileTests {

    @Test
    @DisplayName("should set the elite score to the summed ledger total")
    void shouldSetEliteScoreToSummedLedgerTotal() {
      // Given
      when(reputationEventRepository.sumPointsByUserId(USER_ID)).thenReturn(245);

      // When
      reputationService.reconcile(USER_ID);

      // Then
      verify(userRepository).setEliteScore(USER_ID, 245);
    }
  }
}
