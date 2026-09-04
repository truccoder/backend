package com.socialapp.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.moderation.dto.AppealDto;
import com.socialapp.moderation.dto.AppealRequestDto;
import com.socialapp.moderation.dto.UserViolationDto;
import com.socialapp.moderation.entity.ModerationAppealEntity;
import com.socialapp.moderation.entity.UserViolationEntity;
import com.socialapp.moderation.enums.AppealStatus;
import com.socialapp.moderation.enums.ViolationType;
import com.socialapp.moderation.repository.ModerationAppealRepository;
import com.socialapp.moderation.repository.UserViolationRepository;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link AppealService}, per ISTQB CTFL v4.0.1 (Section 2.2.1 component
 * testing, Section 4.3.2 branch testing, Section 2.1.3 BDD Given/When/Then).
 */
@ExtendWith(MockitoExtension.class)
class AppealServiceTest {

  private static final Integer USER_ID = 7;
  private static final Integer OTHER_USER_ID = 8;
  private static final Long VIOLATION_ID = 55L;
  private static final Long APPEAL_ID = 900L;

  @Mock private ModerationAppealRepository appealRepository;
  @Mock private UserViolationRepository violationRepository;
  @Mock private UserRepository userRepository;
  @Mock private UserBanService userBanService;
  @Mock private NotificationService notificationService;

  @InjectMocks private AppealService appealService;

  @Captor private ArgumentCaptor<ModerationAppealEntity> appealCaptor;
  @Captor private ArgumentCaptor<SendNotificationRequest> notificationCaptor;

  private static UserViolationEntity violation(Long id, Integer userId) {
    return UserViolationEntity.builder()
        .id(id)
        .userId(userId)
        .postId(11)
        .postExcerpt("Check out my new project, link in bio!")
        .violationType(ViolationType.SPAM)
        .description("Admin manual review: repeated advertising")
        .build();
  }

  private static ModerationAppealEntity appeal(Long id, AppealStatus status) {
    return ModerationAppealEntity.builder()
        .id(id)
        .userId(USER_ID)
        .violationId(VIOLATION_ID)
        .reason("It was a link to my own project, not an advert")
        .status(status)
        .build();
  }

  private static AppealRequestDto request() {
    AppealRequestDto dto = new AppealRequestDto();
    dto.setViolationId(VIOLATION_ID);
    dto.setReason("It was a link to my own project, not an advert");
    return dto;
  }

  // =====================================================================
  // getMyViolations
  // =====================================================================

  @Nested
  @DisplayName("getMyViolations")
  class GetMyViolationsTests {

    @Test
    @DisplayName("should flag a violation that already has an appeal waiting on an admin")
    void shouldMarkAppealPending() {
      // Given
      when(violationRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
          .thenReturn(List.of(violation(VIOLATION_ID, USER_ID)));
      // One query for the whole list now, not an exists() per violation.
      when(appealRepository.findViolationIdsWithStatus(List.of(VIOLATION_ID), AppealStatus.PENDING))
          .thenReturn(List.of(VIOLATION_ID));

      // When
      List<UserViolationDto> result = appealService.getMyViolations(USER_ID);

      // Then
      assertThat(result).hasSize(1);
      assertThat(result.get(0).isAppealPending()).isTrue();
      assertThat(result.get(0).getViolationType()).isEqualTo(ViolationType.SPAM);
      // B47: the snapshot taken at record time, not re-derived from postId — it must survive the
      // post being deleted later.
      assertThat(result.get(0).getPostExcerpt())
          .isEqualTo("Check out my new project, link in bio!");
    }
  }

  // =====================================================================
  // submitAppeal
  // =====================================================================

  @Nested
  @DisplayName("submitAppeal")
  class SubmitAppealTests {

    @Test
    @DisplayName("should store a PENDING appeal against the caller's own violation")
    void shouldStorePendingAppeal() {
      // Given
      when(violationRepository.findById(VIOLATION_ID))
          .thenReturn(Optional.of(violation(VIOLATION_ID, USER_ID)));
      when(appealRepository.existsByViolationIdAndStatus(VIOLATION_ID, AppealStatus.PENDING))
          .thenReturn(false);
      when(appealRepository.save(any()))
          .thenAnswer(inv -> inv.getArgument(0, ModerationAppealEntity.class));

      // When
      AppealDto result = appealService.submitAppeal(USER_ID, request());

      // Then
      verify(appealRepository).save(appealCaptor.capture());
      assertThat(appealCaptor.getValue().getStatus()).isEqualTo(AppealStatus.PENDING);
      assertThat(appealCaptor.getValue().getUserId()).isEqualTo(USER_ID);
      // The disputed violation travels with the response so the user's list is readable without
      // a second call per row.
      assertThat(result.getViolationType()).isEqualTo(ViolationType.SPAM);
      // B50: same "which post" context as UserViolationDto (B47), so the appeal list can say what
      // is being disputed without a second lookup.
      assertThat(result.getPostId()).isEqualTo(11);
      assertThat(result.getPostExcerpt()).isEqualTo("Check out my new project, link in bio!");
    }

    @Test
    @DisplayName("should refuse to appeal somebody else's violation")
    void shouldRejectAppealForAnotherUsersViolation() {
      // Given
      when(violationRepository.findById(VIOLATION_ID))
          .thenReturn(Optional.of(violation(VIOLATION_ID, OTHER_USER_ID)));

      // When / Then
      assertThatThrownBy(() -> appealService.submitAppeal(USER_ID, request()))
          .isInstanceOf(ForbiddenException.class);
      verify(appealRepository, never()).save(any());
    }

    @Test
    @DisplayName("should reject a second appeal while the first is still pending")
    void shouldRejectDuplicatePendingAppeal() {
      // Given
      when(violationRepository.findById(VIOLATION_ID))
          .thenReturn(Optional.of(violation(VIOLATION_ID, USER_ID)));
      when(appealRepository.existsByViolationIdAndStatus(VIOLATION_ID, AppealStatus.PENDING))
          .thenReturn(true);

      // When / Then — the partial unique index in V49 is the real guarantee; this turns the
      // ordinary double-submit into a message instead of a constraint violation
      assertThatThrownBy(() -> appealService.submitAppeal(USER_ID, request()))
          .isInstanceOf(ValidationException.class);
      verify(appealRepository, never()).save(any());
    }

    @Test
    @DisplayName("should reject an appeal against a violation that does not exist")
    void shouldRejectUnknownViolation() {
      // Given
      when(violationRepository.findById(VIOLATION_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> appealService.submitAppeal(USER_ID, request()))
          .isInstanceOf(NotFoundException.class);
    }
  }

  // =====================================================================
  // approve / reject
  // =====================================================================

  @Nested
  @DisplayName("approve")
  class ApproveTests {

    @Test
    @DisplayName("should revoke the disputed violation, not merely mark the appeal approved")
    void shouldRevokeViolationOnApproval() {
      // Given
      ModerationAppealEntity pending = appeal(APPEAL_ID, AppealStatus.PENDING);
      when(appealRepository.findById(APPEAL_ID)).thenReturn(Optional.of(pending));
      when(appealRepository.saveAndFlush(any()))
          .thenAnswer(inv -> inv.getArgument(0, ModerationAppealEntity.class));

      // When
      AppealDto result = appealService.approve(APPEAL_ID, 1, "You were right");

      // Then: approving without undoing the violation would tell the user they won and leave
      // them locked out — the appeal would be a formality.
      verify(userBanService).revokeViolation(VIOLATION_ID);
      assertThat(result.getStatus()).isEqualTo(AppealStatus.APPROVED);
      assertThat(result.getReviewerNote()).isEqualTo("You were right");
      assertThat(result.getReviewedAt()).isNotNull();
      // Nothing left to describe: the disputed violation (and its postId/postExcerpt) is gone.
      assertThat(result.getPostId()).isNull();
      assertThat(result.getPostExcerpt()).isNull();

      // B44: the appellant is told they won, not left to notice their own appeal list changed.
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getRecipientId()).isEqualTo(USER_ID);
      assertThat(notificationCaptor.getValue().getType())
          .isEqualTo(NotificationType.APPEAL_APPROVED);
    }

    @Test
    @DisplayName("should keep the appeal itself, detached from the violation it just erased")
    void shouldSurviveItsOwnSuccess() {
      // Given
      ModerationAppealEntity pending = appeal(APPEAL_ID, AppealStatus.PENDING);
      when(appealRepository.findById(APPEAL_ID)).thenReturn(Optional.of(pending));
      when(appealRepository.saveAndFlush(any()))
          .thenAnswer(inv -> inv.getArgument(0, ModerationAppealEntity.class));

      // When
      appealService.approve(APPEAL_ID, 1, "You were right");

      // Then: violation_id is cleared before the violation is deleted. With ON DELETE CASCADE and
      // no clearing, winning an appeal deleted the appeal — the user's record of having won it
      // disappeared and only their losses stayed in "my appeals".
      assertThat(pending.getViolationId()).isNull();
      verify(appealRepository).saveAndFlush(pending);
    }

    @Test
    @DisplayName("should refuse to decide an appeal that was already decided")
    void shouldRejectAlreadyDecidedAppeal() {
      // Given
      when(appealRepository.findById(APPEAL_ID))
          .thenReturn(Optional.of(appeal(APPEAL_ID, AppealStatus.REJECTED)));

      // When / Then — a second approval would go looking for a violation the first one deleted
      assertThatThrownBy(() -> appealService.approve(APPEAL_ID, 1, null))
          .isInstanceOf(ValidationException.class);
      verify(userBanService, never()).revokeViolation(any());
    }
  }

  @Nested
  @DisplayName("reject")
  class RejectTests {

    @Test
    @DisplayName("should leave the violation and the ban standing")
    void shouldNotRevokeOnRejection() {
      // Given
      when(appealRepository.findById(APPEAL_ID))
          .thenReturn(Optional.of(appeal(APPEAL_ID, AppealStatus.PENDING)));
      when(violationRepository.findById(VIOLATION_ID))
          .thenReturn(Optional.of(violation(VIOLATION_ID, USER_ID)));
      when(appealRepository.save(any()))
          .thenAnswer(inv -> inv.getArgument(0, ModerationAppealEntity.class));

      // When
      AppealDto result = appealService.reject(APPEAL_ID, 1, "The rule stands");

      // Then
      assertThat(result.getStatus()).isEqualTo(AppealStatus.REJECTED);
      verify(userBanService, never()).revokeViolation(any());

      // B44: the appellant is told they lost, not left to notice their own appeal list changed.
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getRecipientId()).isEqualTo(USER_ID);
      assertThat(notificationCaptor.getValue().getType())
          .isEqualTo(NotificationType.APPEAL_REJECTED);
    }
  }

  // =====================================================================
  // getAppeals (admin queue)
  // =====================================================================

  @Nested
  @DisplayName("getAppeals")
  class GetAppealsTests {

    @Test
    @DisplayName("should hydrate the appellant and the disputed violation for the admin queue")
    void shouldHydrateQueueRows() {
      // Given
      UserEntity appellant = new UserEntity();
      appellant.setId(USER_ID);
      appellant.setUsername("someone");
      appellant.setFullName("Some One");

      when(appealRepository.findByStatusOrderByCreatedAtAsc(any(), any()))
          .thenReturn(new PageImpl<>(List.of(appeal(APPEAL_ID, AppealStatus.PENDING))));
      when(violationRepository.findAllById(List.of(VIOLATION_ID)))
          .thenReturn(List.of(violation(VIOLATION_ID, USER_ID)));
      when(userRepository.findAllById(List.of(USER_ID))).thenReturn(List.of(appellant));

      // When
      Page<AppealDto> result =
          appealService.getAppeals(AppealStatus.PENDING, PageRequest.of(0, 10));

      // Then: an admin should not need a call per row to know who is arguing about what
      AppealDto dto = result.getContent().get(0);
      assertThat(dto.getUserFullName()).isEqualTo("Some One");
      assertThat(dto.getViolationType()).isEqualTo(ViolationType.SPAM);
      assertThat(dto.getPostId()).isEqualTo(11);
      assertThat(dto.getPostExcerpt()).isEqualTo("Check out my new project, link in bio!");
    }
  }
}
