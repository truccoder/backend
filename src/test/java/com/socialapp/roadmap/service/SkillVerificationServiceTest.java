package com.socialapp.roadmap.service;

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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.github.entity.GithubStatsEntity;
import com.socialapp.github.repository.GithubStatsRepository;
import com.socialapp.roadmap.dto.SkillVerificationRequestDto;
import com.socialapp.roadmap.entity.RoadmapNodeEntity;
import com.socialapp.roadmap.entity.UserRoadmapProgressEntity;
import com.socialapp.roadmap.enums.VerificationStatus;
import com.socialapp.roadmap.enums.VerificationTier;
import com.socialapp.roadmap.repository.RoadmapNodeRepository;
import com.socialapp.roadmap.repository.UserRoadmapProgressRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link SkillVerificationService}, per ISTQB CTFL v4.0.1 (Section
 * 2.2.1 component testing; Section 4.3.2 branch testing over the 4-way {@link VerificationTier}
 * split; Section 4.2.2 boundary value analysis around the AUTO_CERTIFIED GitHub-ownership prefix
 * check; Section 4.3.1 state transition testing over the approve/reject
 * PENDING_APPROVAL-only guard).
 */
@ExtendWith(MockitoExtension.class)
class SkillVerificationServiceTest {

  private static final Integer USER_ID = 1;
  private static final Integer NODE_ID = 10;
  private static final Integer PROGRESS_ID = 100;
  private static final Integer MODERATOR_ID = 2;

  @Mock private UserRoadmapProgressRepository progressRepository;
  @Mock private RoadmapNodeRepository nodeRepository;
  @Mock private UserRepository userRepository;
  @Mock private GithubStatsRepository githubStatsRepository;

  @InjectMocks private SkillVerificationService skillVerificationService;

  private static UserEntity user(Integer id) {
    UserEntity user = new UserEntity();
    user.setId(id);
    return user;
  }

  private static RoadmapNodeEntity node(Integer id) {
    return RoadmapNodeEntity.builder().id(id).name("Node " + id).build();
  }

  private static SkillVerificationRequestDto request(VerificationTier tier, String proofUrl) {
    SkillVerificationRequestDto dto = new SkillVerificationRequestDto();
    dto.setNodeId(NODE_ID);
    dto.setTier(tier);
    dto.setProofUrl(proofUrl);
    return dto;
  }

  private static UserRoadmapProgressEntity pendingProgress() {
    return UserRoadmapProgressEntity.builder()
        .id(PROGRESS_ID)
        .user(user(USER_ID))
        .node(node(NODE_ID))
        .tier(VerificationTier.MOD_VERIFIED)
        .status(VerificationStatus.PENDING_APPROVAL)
        .build();
  }

  // =====================================================================
  // submitVerificationRequest
  // =====================================================================

  @Nested
  @DisplayName("submitVerificationRequest")
  class SubmitVerificationRequestTests {

    @Test
    @DisplayName("should reject when the user does not exist")
    void shouldThrowNotFoundException_whenUserDoesNotExist() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(
              () ->
                  skillVerificationService.submitVerificationRequest(
                      USER_ID, request(VerificationTier.SELF_VERIFIED, null)))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should reject when the roadmap node does not exist")
    void shouldThrowNotFoundException_whenNodeDoesNotExist() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(nodeRepository.findById(NODE_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(
              () ->
                  skillVerificationService.submitVerificationRequest(
                      USER_ID, request(VerificationTier.SELF_VERIFIED, null)))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should immediately verify a SELF_VERIFIED submission")
    void shouldVerifyImmediately_whenTierIsSelfVerified() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(nodeRepository.findById(NODE_ID)).thenReturn(Optional.of(node(NODE_ID)));
      when(progressRepository.findByUserIdAndNodeId(USER_ID, NODE_ID)).thenReturn(Optional.empty());

      // When
      skillVerificationService.submitVerificationRequest(
          USER_ID, request(VerificationTier.SELF_VERIFIED, "https://example.com/proof"));

      // Then
      UserRoadmapProgressEntity saved = captureSaved();
      assertThat(saved.getStatus()).isEqualTo(VerificationStatus.VERIFIED);
      assertThat(saved.getVerifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("should leave a MOD_VERIFIED submission pending approval")
    void shouldStayPending_whenTierIsModVerified() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(nodeRepository.findById(NODE_ID)).thenReturn(Optional.of(node(NODE_ID)));
      when(progressRepository.findByUserIdAndNodeId(USER_ID, NODE_ID)).thenReturn(Optional.empty());

      // When
      skillVerificationService.submitVerificationRequest(
          USER_ID, request(VerificationTier.MOD_VERIFIED, "https://example.com/proof"));

      // Then
      UserRoadmapProgressEntity saved = captureSaved();
      assertThat(saved.getStatus()).isEqualTo(VerificationStatus.PENDING_APPROVAL);
      assertThat(saved.getVerifiedAt()).isNull();
    }

    @Test
    @DisplayName("should leave a QUIZ_VERIFIED submission pending approval")
    void shouldStayPending_whenTierIsQuizVerified() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(nodeRepository.findById(NODE_ID)).thenReturn(Optional.of(node(NODE_ID)));
      when(progressRepository.findByUserIdAndNodeId(USER_ID, NODE_ID)).thenReturn(Optional.empty());

      // When
      skillVerificationService.submitVerificationRequest(
          USER_ID, request(VerificationTier.QUIZ_VERIFIED, null));

      // Then
      UserRoadmapProgressEntity saved = captureSaved();
      assertThat(saved.getStatus()).isEqualTo(VerificationStatus.PENDING_APPROVAL);
    }

    @Test
    @DisplayName("should reject AUTO_CERTIFIED when the user has no linked GitHub account")
    void shouldReject_whenAutoCertifiedAndNoGithubAccountLinked() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(nodeRepository.findById(NODE_ID)).thenReturn(Optional.of(node(NODE_ID)));
      when(progressRepository.findByUserIdAndNodeId(USER_ID, NODE_ID)).thenReturn(Optional.empty());
      when(githubStatsRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

      // When
      skillVerificationService.submitVerificationRequest(
          USER_ID,
          request(VerificationTier.AUTO_CERTIFIED, "https://github.com/octocat/hello-world"));

      // Then
      UserRoadmapProgressEntity saved = captureSaved();
      assertThat(saved.getStatus()).isEqualTo(VerificationStatus.REJECTED);
    }

    @Test
    @DisplayName("should reject AUTO_CERTIFIED when the proof URL is null")
    void shouldReject_whenAutoCertifiedAndProofUrlIsNull() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(nodeRepository.findById(NODE_ID)).thenReturn(Optional.of(node(NODE_ID)));
      when(progressRepository.findByUserIdAndNodeId(USER_ID, NODE_ID)).thenReturn(Optional.empty());

      // When
      skillVerificationService.submitVerificationRequest(
          USER_ID, request(VerificationTier.AUTO_CERTIFIED, null));

      // Then
      UserRoadmapProgressEntity saved = captureSaved();
      assertThat(saved.getStatus()).isEqualTo(VerificationStatus.REJECTED);
    }

    @Test
    @DisplayName("should reject AUTO_CERTIFIED when the proof URL is blank")
    void shouldReject_whenAutoCertifiedAndProofUrlIsBlank() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(nodeRepository.findById(NODE_ID)).thenReturn(Optional.of(node(NODE_ID)));
      when(progressRepository.findByUserIdAndNodeId(USER_ID, NODE_ID)).thenReturn(Optional.empty());

      // When
      skillVerificationService.submitVerificationRequest(
          USER_ID, request(VerificationTier.AUTO_CERTIFIED, "   "));

      // Then
      UserRoadmapProgressEntity saved = captureSaved();
      assertThat(saved.getStatus()).isEqualTo(VerificationStatus.REJECTED);
    }

    @Test
    @DisplayName(
        "should reject AUTO_CERTIFIED when the proof URL belongs to a different GitHub user"
            + " whose name merely starts with the same prefix")
    void shouldReject_whenAutoCertifiedAndProofUrlBelongsToAnotherUser() {
      // Given: linked username is "john", proof points at "johnson" — a naive startsWith("john")
      // without the trailing slash would wrongly accept this.
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(nodeRepository.findById(NODE_ID)).thenReturn(Optional.of(node(NODE_ID)));
      when(progressRepository.findByUserIdAndNodeId(USER_ID, NODE_ID)).thenReturn(Optional.empty());
      when(githubStatsRepository.findByUserId(USER_ID))
          .thenReturn(Optional.of(GithubStatsEntity.builder().githubUsername("john").build()));

      // When
      skillVerificationService.submitVerificationRequest(
          USER_ID,
          request(VerificationTier.AUTO_CERTIFIED, "https://github.com/johnson/side-project"));

      // Then
      UserRoadmapProgressEntity saved = captureSaved();
      assertThat(saved.getStatus()).isEqualTo(VerificationStatus.REJECTED);
    }

    @Test
    @DisplayName("should verify AUTO_CERTIFIED when the proof URL is the user's own repository")
    void shouldVerify_whenAutoCertifiedAndProofUrlIsOwnRepository() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(nodeRepository.findById(NODE_ID)).thenReturn(Optional.of(node(NODE_ID)));
      when(progressRepository.findByUserIdAndNodeId(USER_ID, NODE_ID)).thenReturn(Optional.empty());
      when(githubStatsRepository.findByUserId(USER_ID))
          .thenReturn(Optional.of(GithubStatsEntity.builder().githubUsername("octocat").build()));

      // When
      skillVerificationService.submitVerificationRequest(
          USER_ID,
          request(VerificationTier.AUTO_CERTIFIED, "https://github.com/octocat/hello-world"));

      // Then
      UserRoadmapProgressEntity saved = captureSaved();
      assertThat(saved.getStatus()).isEqualTo(VerificationStatus.VERIFIED);
      assertThat(saved.getVerifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("should update the existing progress row instead of creating a new one")
    void shouldUpdateExistingProgress_onResubmission() {
      // Given: a prior submission already sits VERIFIED via SELF_VERIFIED; the user now
      // resubmits the same node under MOD_VERIFIED.
      UserRoadmapProgressEntity existing =
          UserRoadmapProgressEntity.builder()
              .id(PROGRESS_ID)
              .user(user(USER_ID))
              .node(node(NODE_ID))
              .tier(VerificationTier.SELF_VERIFIED)
              .status(VerificationStatus.VERIFIED)
              .build();
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(nodeRepository.findById(NODE_ID)).thenReturn(Optional.of(node(NODE_ID)));
      when(progressRepository.findByUserIdAndNodeId(USER_ID, NODE_ID))
          .thenReturn(Optional.of(existing));

      // When
      skillVerificationService.submitVerificationRequest(
          USER_ID, request(VerificationTier.MOD_VERIFIED, "https://example.com/new-proof"));

      // Then
      UserRoadmapProgressEntity saved = captureSaved();
      assertThat(saved.getId()).isEqualTo(PROGRESS_ID);
      assertThat(saved.getTier()).isEqualTo(VerificationTier.MOD_VERIFIED);
      assertThat(saved.getStatus()).isEqualTo(VerificationStatus.PENDING_APPROVAL);
      assertThat(saved.getProofUrl()).isEqualTo("https://example.com/new-proof");
    }

    @Test
    @DisplayName("should leave the default PENDING_APPROVAL status untouched when the tier is null")
    void shouldLeaveDefaultStatus_whenTierIsNull() {
      // Given: none of the SELF_VERIFIED/MOD_VERIFIED/QUIZ_VERIFIED/AUTO_CERTIFIED branches match
      // a null tier, so the newly-built progress keeps its builder default.
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(nodeRepository.findById(NODE_ID)).thenReturn(Optional.of(node(NODE_ID)));
      when(progressRepository.findByUserIdAndNodeId(USER_ID, NODE_ID)).thenReturn(Optional.empty());

      // When
      skillVerificationService.submitVerificationRequest(USER_ID, request(null, null));

      // Then
      UserRoadmapProgressEntity saved = captureSaved();
      assertThat(saved.getTier()).isNull();
      assertThat(saved.getStatus()).isEqualTo(VerificationStatus.PENDING_APPROVAL);
    }

    private UserRoadmapProgressEntity captureSaved() {
      ArgumentCaptor<UserRoadmapProgressEntity> captor =
          ArgumentCaptor.forClass(UserRoadmapProgressEntity.class);
      verify(progressRepository).save(captor.capture());
      return captor.getValue();
    }
  }

  // =====================================================================
  // approveRequest
  // =====================================================================

  @Nested
  @DisplayName("approveRequest")
  class ApproveRequestTests {

    @Test
    @DisplayName("should reject when the progress does not exist")
    void shouldThrowNotFoundException_whenProgressDoesNotExist() {
      // Given
      when(progressRepository.findById(PROGRESS_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> skillVerificationService.approveRequest(PROGRESS_ID, MODERATOR_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should reject a request that is not PENDING_APPROVAL")
    void shouldThrowIllegalStateException_whenNotPendingApproval() {
      // Given
      UserRoadmapProgressEntity alreadyVerified = pendingProgress();
      alreadyVerified.setStatus(VerificationStatus.VERIFIED);
      when(progressRepository.findById(PROGRESS_ID)).thenReturn(Optional.of(alreadyVerified));

      // When / Then
      assertThatThrownBy(() -> skillVerificationService.approveRequest(PROGRESS_ID, MODERATOR_ID))
          .isInstanceOf(IllegalStateException.class);
      verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("should reject when the moderator does not exist")
    void shouldThrowNotFoundException_whenModeratorDoesNotExist() {
      // Given
      when(progressRepository.findById(PROGRESS_ID)).thenReturn(Optional.of(pendingProgress()));
      when(userRepository.findById(MODERATOR_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> skillVerificationService.approveRequest(PROGRESS_ID, MODERATOR_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should verify a pending request and record the moderator")
    void shouldApprove_whenPendingAndModeratorExists() {
      // Given
      UserRoadmapProgressEntity progress = pendingProgress();
      UserEntity moderator = user(MODERATOR_ID);
      when(progressRepository.findById(PROGRESS_ID)).thenReturn(Optional.of(progress));
      when(userRepository.findById(MODERATOR_ID)).thenReturn(Optional.of(moderator));

      // When
      skillVerificationService.approveRequest(PROGRESS_ID, MODERATOR_ID);

      // Then
      assertThat(progress.getStatus()).isEqualTo(VerificationStatus.VERIFIED);
      assertThat(progress.getVerifier()).isEqualTo(moderator);
      assertThat(progress.getVerifiedAt()).isNotNull();
      verify(progressRepository).save(progress);
    }
  }

  // =====================================================================
  // rejectRequest
  // =====================================================================

  @Nested
  @DisplayName("rejectRequest")
  class RejectRequestTests {

    @Test
    @DisplayName("should reject when the progress does not exist")
    void shouldThrowNotFoundException_whenProgressDoesNotExist() {
      // Given
      when(progressRepository.findById(PROGRESS_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> skillVerificationService.rejectRequest(PROGRESS_ID, MODERATOR_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should reject a request that is not PENDING_APPROVAL")
    void shouldThrowIllegalStateException_whenNotPendingApproval() {
      // Given
      UserRoadmapProgressEntity alreadyRejected = pendingProgress();
      alreadyRejected.setStatus(VerificationStatus.REJECTED);
      when(progressRepository.findById(PROGRESS_ID)).thenReturn(Optional.of(alreadyRejected));

      // When / Then
      assertThatThrownBy(() -> skillVerificationService.rejectRequest(PROGRESS_ID, MODERATOR_ID))
          .isInstanceOf(IllegalStateException.class);
      verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("should reject when the moderator does not exist")
    void shouldThrowNotFoundException_whenModeratorDoesNotExist() {
      // Given
      when(progressRepository.findById(PROGRESS_ID)).thenReturn(Optional.of(pendingProgress()));
      when(userRepository.findById(MODERATOR_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> skillVerificationService.rejectRequest(PROGRESS_ID, MODERATOR_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should reject a pending request and record the moderator")
    void shouldReject_whenPendingAndModeratorExists() {
      // Given
      UserRoadmapProgressEntity progress = pendingProgress();
      UserEntity moderator = user(MODERATOR_ID);
      when(progressRepository.findById(PROGRESS_ID)).thenReturn(Optional.of(progress));
      when(userRepository.findById(MODERATOR_ID)).thenReturn(Optional.of(moderator));

      // When
      skillVerificationService.rejectRequest(PROGRESS_ID, MODERATOR_ID);

      // Then
      assertThat(progress.getStatus()).isEqualTo(VerificationStatus.REJECTED);
      assertThat(progress.getVerifier()).isEqualTo(moderator);
      assertThat(progress.getVerifiedAt()).isNotNull();
      verify(progressRepository).save(progress);
    }
  }

  // =====================================================================
  // getPendingRequests
  // =====================================================================

  @Nested
  @DisplayName("getPendingRequests")
  class GetPendingRequestsTests {

    @Test
    @DisplayName("should return every progress row awaiting moderator approval")
    void shouldReturnPendingProgressList() {
      // Given
      List<UserRoadmapProgressEntity> pending = List.of(pendingProgress());
      when(progressRepository.findByStatus(VerificationStatus.PENDING_APPROVAL))
          .thenReturn(pending);

      // When
      List<UserRoadmapProgressEntity> result = skillVerificationService.getPendingRequests();

      // Then
      assertThat(result).isEqualTo(pending);
    }
  }
}
