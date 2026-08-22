package com.socialapp.roadmap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.github.entity.GithubStatsEntity;
import com.socialapp.github.repository.GithubStatsRepository;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.reputation.entity.enums.RepSourceType;
import com.socialapp.reputation.event.ReputationEventPublisher;
import com.socialapp.roadmap.dto.PendingVerificationDto;
import com.socialapp.roadmap.dto.RoadmapProgressDto;
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
  @Mock private ReputationEventPublisher reputationEventPublisher;
  @Mock private NotificationService notificationService;

  @Captor private ArgumentCaptor<SendNotificationRequest> notificationCaptor;

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
      verify(reputationEventPublisher)
          .award(USER_ID, RepSourceType.ROADMAP_SELF_VERIFIED, USER_ID + ":" + NODE_ID);
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
      verifyNoInteractions(reputationEventPublisher);
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
      verify(reputationEventPublisher)
          .award(USER_ID, RepSourceType.ROADMAP_NODE_VERIFIED, USER_ID + ":" + NODE_ID);
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
      verify(reputationEventPublisher)
          .award(USER_ID, RepSourceType.ROADMAP_NODE_VERIFIED, USER_ID + ":" + NODE_ID);
    }

    @Test
    @DisplayName("should tell the claimant their skill was verified")
    void shouldNotifyClaimantOnApproval() {
      // Given — the loop this product is built around (real work → ledger → reputation) used to
      // end here in silence: the points were awarded and nobody was told, so the only way to find
      // out was to reopen the page and notice the score had moved.
      UserRoadmapProgressEntity progress = pendingProgress();
      when(progressRepository.findById(PROGRESS_ID)).thenReturn(Optional.of(progress));
      when(userRepository.findById(MODERATOR_ID)).thenReturn(Optional.of(user(MODERATOR_ID)));

      // When
      skillVerificationService.approveRequest(PROGRESS_ID, MODERATOR_ID);

      // Then
      verify(notificationService).send(notificationCaptor.capture());
      SendNotificationRequest sent = notificationCaptor.getValue();
      assertThat(sent.getType()).isEqualTo(NotificationType.SKILL_VERIFIED);
      assertThat(sent.getRecipientId()).isEqualTo(USER_ID);
      assertThat(sent.getReferenceId()).isEqualTo(NODE_ID);
      assertThat(sent.getReferenceType()).isEqualTo("ROADMAP_NODE");
      assertThat(sent.getBody()).contains("Node " + NODE_ID);
    }

    @Test
    @DisplayName("should not name the moderator who ruled on the claim")
    void shouldNotNameTheModerator() {
      // Given — which moderator approved a skill is internal (see RoadmapProgressDto), and
      // NotificationResponseDto hands actorId straight to the client. A null actor also skips the
      // block check, which is right: a moderation outcome is not a person acting on you, and
      // letting a block swallow it would leave the claim looking unanswered forever.
      when(progressRepository.findById(PROGRESS_ID)).thenReturn(Optional.of(pendingProgress()));
      when(userRepository.findById(MODERATOR_ID)).thenReturn(Optional.of(user(MODERATOR_ID)));

      // When
      skillVerificationService.approveRequest(PROGRESS_ID, MODERATOR_ID);

      // Then
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getActorId()).isNull();
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

    @Test
    @DisplayName("should tell the claimant their request was declined")
    void shouldNotifyClaimantOnRejection() {
      // Given — a refusal that arrives silently is indistinguishable from one still in the queue,
      // so without this the claimant waits forever on an answer already given
      when(progressRepository.findById(PROGRESS_ID)).thenReturn(Optional.of(pendingProgress()));
      when(userRepository.findById(MODERATOR_ID)).thenReturn(Optional.of(user(MODERATOR_ID)));

      // When
      skillVerificationService.rejectRequest(PROGRESS_ID, MODERATOR_ID);

      // Then
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getType())
          .isEqualTo(NotificationType.SKILL_REJECTED);
      assertThat(notificationCaptor.getValue().getRecipientId()).isEqualTo(USER_ID);
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
      when(progressRepository.findByStatusWithUserAndNode(VerificationStatus.PENDING_APPROVAL))
          .thenReturn(List.of(pendingProgress()));

      // When
      List<PendingVerificationDto> result = skillVerificationService.getPendingRequests();

      // Then
      assertThat(result)
          .singleElement()
          .satisfies(
              dto -> {
                assertThat(dto.getProgressId()).isEqualTo(PROGRESS_ID);
                assertThat(dto.getUserId()).isEqualTo(USER_ID);
                assertThat(dto.getNodeId()).isEqualTo(NODE_ID);
                assertThat(dto.getTier()).isEqualTo(VerificationTier.MOD_VERIFIED);
              });
    }

    @Test
    @DisplayName("should not expose the requester's password hash")
    void shouldNotExposePasswordHash() {
      // Given
      UserRoadmapProgressEntity progress = pendingProgress();
      progress.getUser().setPassword("$2a$10$hashed");
      when(progressRepository.findByStatusWithUserAndNode(VerificationStatus.PENDING_APPROVAL))
          .thenReturn(List.of(progress));

      // When
      List<PendingVerificationDto> result = skillVerificationService.getPendingRequests();

      // Then — the DTO has no field that could carry it at all.
      assertThat(result)
          .singleElement()
          .extracting(Object::toString)
          .asString()
          .doesNotContain("hashed");
    }
  }

  // =====================================================================
  // getProgressForUser  (D1 — the "verified skills" card)
  // =====================================================================

  @Nested
  @DisplayName("getProgressForUser")
  class GetProgressForUserTests {

    private UserRoadmapProgressEntity progress(VerificationStatus status, String nodeName) {
      RoadmapNodeEntity node = RoadmapNodeEntity.builder().id(1).name(nodeName).build();
      UserEntity owner = new UserEntity();
      owner.setId(1);
      return UserRoadmapProgressEntity.builder()
          .id(1)
          .user(owner)
          .node(node)
          .tier(VerificationTier.SELF_VERIFIED)
          .status(status)
          .proofUrl("https://private.example/certificate.pdf")
          .build();
    }

    @Test
    @DisplayName("should show a stranger only the skills that were actually verified")
    void shouldHideUnverifiedFromStrangers() {
      // Given
      when(progressRepository.findByUserIdWithNode(1))
          .thenReturn(
              List.of(
                  progress(VerificationStatus.VERIFIED, "Java"),
                  progress(VerificationStatus.PENDING_APPROVAL, "Kubernetes"),
                  progress(VerificationStatus.REJECTED, "Rust")));

      // When
      List<RoadmapProgressDto> result = skillVerificationService.getProgressForUser(1, 2);

      // Then: a rejected verification is a record of a claim that was turned down; publishing it
      // turns a "verified skills" card into a list of somebody's failed claims
      assertThat(result).hasSize(1);
      assertThat(result.get(0).getNodeName()).isEqualTo("Java");
    }

    @Test
    @DisplayName("should show the owner their pending and rejected skills too")
    void shouldShowEverythingToOwner() {
      // Given
      when(progressRepository.findByUserIdWithNode(1))
          .thenReturn(
              List.of(
                  progress(VerificationStatus.VERIFIED, "Java"),
                  progress(VerificationStatus.PENDING_APPROVAL, "Kubernetes")));

      // When
      List<RoadmapProgressDto> result = skillVerificationService.getProgressForUser(1, 1);

      // Then
      assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("should treat a signed-out visitor as a stranger")
    void shouldTreatGuestAsStranger() {
      // Given: viewerId is null for a guest, and this endpoint is part of the public profile
      when(progressRepository.findByUserIdWithNode(1))
          .thenReturn(List.of(progress(VerificationStatus.PENDING_APPROVAL, "Kubernetes")));

      // When
      List<RoadmapProgressDto> result = skillVerificationService.getProgressForUser(1, null);

      // Then
      assertThat(result).isEmpty();
    }
  }
}
