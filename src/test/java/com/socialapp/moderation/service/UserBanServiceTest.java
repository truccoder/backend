package com.socialapp.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.moderation.entity.UserBanEntity;
import com.socialapp.moderation.entity.UserViolationEntity;
import com.socialapp.moderation.enums.ViolationSeverity;
import com.socialapp.moderation.enums.ViolationType;
import com.socialapp.moderation.repository.UserBanRepository;
import com.socialapp.moderation.repository.UserViolationRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link UserBanService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing — including the
 * exhaustive {@code switch} over {@link ViolationType} in {@code determineSeverity}, Section 2.1.3
 * BDD Given/When/Then) — see {@code PostServiceTest} for the full rationale.
 */
@ExtendWith(MockitoExtension.class)
class UserBanServiceTest {

  private static final Integer USER_ID = 1;
  private static final Integer POST_ID = 100;

  @Mock private UserViolationRepository violationRepository;
  @Mock private UserBanRepository userBanRepository;
  @Mock private UserRepository userRepository;

  @InjectMocks private UserBanService userBanService;

  @Captor private ArgumentCaptor<UserViolationEntity> violationCaptor;
  @Captor private ArgumentCaptor<UserBanEntity> banCaptor;

  private static UserEntity user(Integer id, OffsetDateTime bannedUntil) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setBannedUntil(bannedUntil);
    return user;
  }

  // =====================================================================
  // isUserBanned
  // =====================================================================

  @Nested
  @DisplayName("isUserBanned")
  class IsUserBannedTests {

    @Test
    @DisplayName("should return true when the user is currently banned")
    void shouldReturnTrue_whenUserIsBanned() {
      when(userRepository.findById(USER_ID))
          .thenReturn(Optional.of(user(USER_ID, OffsetDateTime.now().plusDays(1))));
      assertThat(userBanService.isUserBanned(USER_ID)).isTrue();
    }

    @Test
    @DisplayName("should return false when the user is not banned")
    void shouldReturnFalse_whenUserIsNotBanned() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID, null)));
      assertThat(userBanService.isUserBanned(USER_ID)).isFalse();
    }

    @Test
    @DisplayName("should return false when the user does not exist")
    void shouldReturnFalse_whenUserDoesNotExist() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      assertThat(userBanService.isUserBanned(USER_ID)).isFalse();
    }
  }

  // =====================================================================
  // getBanExpiry
  // =====================================================================

  @Nested
  @DisplayName("getBanExpiry")
  class GetBanExpiryTests {

    @Test
    @DisplayName("should return the ban expiry when the user is banned")
    void shouldReturnExpiry_whenUserBanned() {
      OffsetDateTime expiry = OffsetDateTime.now().plusDays(1);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID, expiry)));
      assertThat(userBanService.getBanExpiry(USER_ID)).isEqualTo(expiry);
    }

    @Test
    @DisplayName("should return null when the user does not exist")
    void shouldReturnNull_whenUserDoesNotExist() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      assertThat(userBanService.getBanExpiry(USER_ID)).isNull();
    }
  }

  // =====================================================================
  // recordViolation — ban threshold logic
  // =====================================================================

  @Nested
  @DisplayName("recordViolation")
  class RecordViolationTests {

    @Test
    @DisplayName("should record the violation without banning when below the threshold")
    void shouldRecordWithoutBanning_whenBelowThreshold() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID, null)));
      when(violationRepository.countRecentViolations(eq(USER_ID), any())).thenReturn(1L);

      // When
      userBanService.recordViolation(USER_ID, POST_ID, ViolationType.SPAM, "spammy");

      // Then
      verify(violationRepository).save(any());
      verify(userBanRepository, never()).save(any());
    }

    @Test
    @DisplayName("should not ban again when the user is already banned")
    void shouldNotBanAgain_whenAlreadyBanned() {
      // Given
      when(userRepository.findById(USER_ID))
          .thenReturn(Optional.of(user(USER_ID, OffsetDateTime.now().plusDays(1))));
      when(violationRepository.countRecentViolations(eq(USER_ID), any())).thenReturn(5L);

      // When
      userBanService.recordViolation(USER_ID, POST_ID, ViolationType.SPAM, "spammy");

      // Then
      verify(userBanRepository, never()).save(any());
    }

    @Test
    @DisplayName(
        "should ban the user once the threshold is reached and they are not already banned")
    void shouldBanUser_whenThresholdReachedAndNotBanned() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID, null)));
      when(violationRepository.countRecentViolations(eq(USER_ID), any())).thenReturn(2L);

      // When
      userBanService.recordViolation(USER_ID, POST_ID, ViolationType.SPAM, "spammy");

      // Then
      verify(userBanRepository).save(banCaptor.capture());
      assertThat(banCaptor.getValue().getUserId()).isEqualTo(USER_ID);
      assertThat(banCaptor.getValue().getPostId()).isEqualTo(POST_ID);
      verify(userRepository).save(any());
    }

    @Test
    @DisplayName("should skip updating the user record when banning a user that no longer exists")
    void shouldSkipUserUpdate_whenUserDeletedDuringBan() {
      // Given: findById returns empty on the *second* lookup (inside issueBan) but must return a
      // user on the first lookup (inside getViolationCountStartDate -> getBanExpiry), so the
      // threshold path is still reached.
      when(userRepository.findById(USER_ID))
          .thenReturn(Optional.of(user(USER_ID, null)))
          .thenReturn(Optional.empty());
      when(violationRepository.countRecentViolations(eq(USER_ID), any())).thenReturn(2L);

      // When
      userBanService.recordViolation(USER_ID, POST_ID, ViolationType.SPAM, "spammy");

      // Then
      verify(userBanRepository).save(any());
      verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName(
        "should count violations from the previous ban's expiry when it has already passed")
    void shouldCountFromPastBanExpiry_whenExpired() {
      // Given
      OffsetDateTime pastExpiry = OffsetDateTime.now().minusDays(1);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID, pastExpiry)));
      ArgumentCaptor<OffsetDateTime> sinceCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
      when(violationRepository.countRecentViolations(eq(USER_ID), sinceCaptor.capture()))
          .thenReturn(0L);

      // When
      userBanService.recordViolation(USER_ID, POST_ID, ViolationType.SPAM, "spammy");

      // Then
      assertThat(sinceCaptor.getValue()).isEqualTo(pastExpiry);
    }

    @Test
    @DisplayName("should count violations from the epoch when there is no ban expiry at all")
    void shouldCountFromEpoch_whenNoBanExpiry() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID, null)));
      ArgumentCaptor<OffsetDateTime> sinceCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
      when(violationRepository.countRecentViolations(eq(USER_ID), sinceCaptor.capture()))
          .thenReturn(0L);

      // When
      userBanService.recordViolation(USER_ID, POST_ID, ViolationType.SPAM, "spammy");

      // Then
      assertThat(sinceCaptor.getValue().getYear()).isEqualTo(2000);
    }

    @Test
    @DisplayName("should count violations from the epoch when the previous ban is still active")
    void shouldCountFromEpoch_whenBanStillActive() {
      // Given
      OffsetDateTime futureExpiry = OffsetDateTime.now().plusDays(1);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID, futureExpiry)));
      ArgumentCaptor<OffsetDateTime> sinceCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
      when(violationRepository.countRecentViolations(eq(USER_ID), sinceCaptor.capture()))
          .thenReturn(0L);

      // When
      userBanService.recordViolation(USER_ID, POST_ID, ViolationType.SPAM, "spammy");

      // Then
      assertThat(sinceCaptor.getValue().getYear()).isEqualTo(2000);
    }

    @Test
    @DisplayName("should classify HATE_SPEECH as CRITICAL severity")
    void shouldClassifyCritical() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID, null)));
      when(violationRepository.countRecentViolations(eq(USER_ID), any())).thenReturn(0L);
      userBanService.recordViolation(USER_ID, POST_ID, ViolationType.HATE_SPEECH, "d");
      verify(violationRepository).save(violationCaptor.capture());
      assertThat(violationCaptor.getValue().getSeverity()).isEqualTo(ViolationSeverity.CRITICAL);
    }

    @Test
    @DisplayName("should classify NSFW as HIGH severity")
    void shouldClassifyHigh() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID, null)));
      when(violationRepository.countRecentViolations(eq(USER_ID), any())).thenReturn(0L);
      userBanService.recordViolation(USER_ID, POST_ID, ViolationType.NSFW, "d");
      verify(violationRepository).save(violationCaptor.capture());
      assertThat(violationCaptor.getValue().getSeverity()).isEqualTo(ViolationSeverity.HIGH);
    }

    @Test
    @DisplayName("should classify INSULT as MEDIUM severity")
    void shouldClassifyMedium() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID, null)));
      when(violationRepository.countRecentViolations(eq(USER_ID), any())).thenReturn(0L);
      userBanService.recordViolation(USER_ID, POST_ID, ViolationType.INSULT, "d");
      verify(violationRepository).save(violationCaptor.capture());
      assertThat(violationCaptor.getValue().getSeverity()).isEqualTo(ViolationSeverity.MEDIUM);
    }

    @Test
    @DisplayName("should classify SPAM as LOW severity")
    void shouldClassifyLow() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID, null)));
      when(violationRepository.countRecentViolations(eq(USER_ID), any())).thenReturn(0L);
      userBanService.recordViolation(USER_ID, POST_ID, ViolationType.SPAM, "d");
      verify(violationRepository).save(violationCaptor.capture());
      assertThat(violationCaptor.getValue().getSeverity()).isEqualTo(ViolationSeverity.LOW);
    }
  }

  // =====================================================================
  // revokeViolation  (E3 — an upheld appeal has to undo something)
  // =====================================================================

  @Nested
  @DisplayName("revokeViolation")
  class RevokeViolationTests {

    @Test
    @DisplayName("should delete the violation and lift the ban when the count drops below two")
    void shouldLiftBanWhenBelowThreshold() {
      // Given
      UserViolationEntity violation =
          UserViolationEntity.builder().id(5L).userId(1).violationType(ViolationType.SPAM).build();
      UserEntity user = new UserEntity();
      user.setId(1);
      user.setBannedUntil(OffsetDateTime.now().plusDays(7));

      when(violationRepository.findById(5L)).thenReturn(Optional.of(violation));
      when(userRepository.findById(1)).thenReturn(Optional.of(user));
      when(violationRepository.countRecentViolations(eq(1), any())).thenReturn(1L);

      // When
      userBanService.revokeViolation(5L);

      // Then
      verify(violationRepository).delete(violation);
      assertThat(user.getBannedUntil()).isNull();
    }

    @Test
    @DisplayName("should keep the ban when the remaining violations still reach the threshold")
    void shouldKeepBanWhenStillOverThreshold() {
      // Given: a user with three violations who successfully appeals one still has two, and two
      // is what got them banned
      UserViolationEntity violation =
          UserViolationEntity.builder().id(5L).userId(1).violationType(ViolationType.SPAM).build();
      OffsetDateTime bannedUntil = OffsetDateTime.now().plusDays(7);
      UserEntity user = new UserEntity();
      user.setId(1);
      user.setBannedUntil(bannedUntil);

      when(violationRepository.findById(5L)).thenReturn(Optional.of(violation));
      when(userRepository.findById(1)).thenReturn(Optional.of(user));
      when(violationRepository.countRecentViolations(eq(1), any())).thenReturn(2L);

      // When
      userBanService.revokeViolation(5L);

      // Then
      verify(violationRepository).delete(violation);
      assertThat(user.getBannedUntil()).isEqualTo(bannedUntil);
    }

    @Test
    @DisplayName("should do nothing for a violation that no longer exists")
    void shouldNoOpForMissingViolation() {
      // Given
      when(violationRepository.findById(5L)).thenReturn(Optional.empty());

      // When
      userBanService.revokeViolation(5L);

      // Then
      verify(violationRepository, never()).delete(any());
    }
  }
}
