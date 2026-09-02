package com.socialapp.roadmap.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.ConflictException;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.github.repository.GithubStatsRepository;
import com.socialapp.notifications.NotificationMessages;
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

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SkillVerificationService {

  private final UserRoadmapProgressRepository progressRepository;
  private final RoadmapNodeRepository nodeRepository;
  private final UserRepository userRepository;
  private final GithubStatsRepository githubStatsRepository;
  private final ReputationEventPublisher reputationEventPublisher;
  private final NotificationService notificationService;

  /**
   * One user's roadmap progress, for the "verified skills" card on their profile.
   *
   * <p><b>Strangers see verified skills only.</b> The owner gets every row, including what is still
   * waiting on a moderator and what was turned down; nobody else does. A rejected verification is a
   * record of someone claiming a skill and being told no — publishing that turns a profile card
   * into a list of a person's failed claims, which is not what "verified skills" means. A pending
   * one is equally not a fact yet.
   *
   * <p>{@code viewerId} is null for a signed-out visitor, which lands on the stranger branch: this
   * endpoint is part of the public profile.
   */
  @Transactional(readOnly = true)
  public List<RoadmapProgressDto> getProgressForUser(Integer userId, Integer viewerId) {
    boolean isOwner = userId.equals(viewerId);

    return progressRepository.findByUserIdWithNode(userId).stream()
        .filter(p -> isOwner || VerificationStatus.VERIFIED.equals(p.getStatus()))
        .map(RoadmapProgressDto::from)
        .toList();
  }

  /**
   * Files or re-files a skill claim and returns the resulting progress row.
   *
   * <p>Returning the row rather than {@code void} (B21) is what lets the client show the outcome
   * without a follow-up read: the four tiers resolve differently and some resolve immediately —
   * {@code SELF_VERIFIED} writes {@code VERIFIED} in this same call, {@code AUTO_CERTIFIED} comes
   * back either {@code VERIFIED} or {@code REJECTED} depending on the GitHub check, and only
   * {@code MOD_VERIFIED}/{@code QUIZ_VERIFIED} land on {@code PENDING_APPROVAL}. The DTO is the
   * public one ({@code RoadmapProgressDto}); it deliberately carries no proof fields, so this is
   * safe to hand straight back to the claimant.
   */
  @Transactional
  public RoadmapProgressDto submitVerificationRequest(
      Integer userId, SkillVerificationRequestDto dto) {
    UserEntity user =
        userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    RoadmapNodeEntity node =
        nodeRepository
            .findById(dto.getNodeId())
            .orElseThrow(() -> new NotFoundException("Node not found"));

    Optional<UserRoadmapProgressEntity> existingProgress =
        progressRepository.findByUserIdAndNodeId(userId, dto.getNodeId());

    existingProgress.ifPresent(existing -> requireResubmittable(existing, dto.getTier()));

    UserRoadmapProgressEntity progress =
        existingProgress.orElseGet(
            () -> UserRoadmapProgressEntity.builder().user(user).node(node).build());

    progress.setTier(dto.getTier());
    progress.setProofUrl(dto.getProofUrl());
    progress.setProofImageKey(dto.getProofImageKey());

    RepSourceType awardType = null;

    if (dto.getTier() == VerificationTier.SELF_VERIFIED) {
      progress.setStatus(VerificationStatus.VERIFIED);
      progress.setVerifiedAt(OffsetDateTime.now());
      awardType = RepSourceType.ROADMAP_SELF_VERIFIED;
    } else if (dto.getTier() == VerificationTier.MOD_VERIFIED
        || dto.getTier() == VerificationTier.QUIZ_VERIFIED) {
      progress.setStatus(VerificationStatus.PENDING_APPROVAL);
    } else if (dto.getTier() == VerificationTier.AUTO_CERTIFIED) {
      boolean isValid = verifyViaExternalApi(user, dto.getProofUrl());
      if (isValid) {
        progress.setStatus(VerificationStatus.VERIFIED);
        progress.setVerifiedAt(OffsetDateTime.now());
        awardType = RepSourceType.ROADMAP_NODE_VERIFIED;
      } else {
        progress.setStatus(VerificationStatus.REJECTED);
      }
    }

    progressRepository.save(progress);

    if (awardType != null) {
      awardForNode(userId, dto.getNodeId(), awardType);
    }

    // Built from the in-memory row, not the save() return: every field the DTO reads (node, tier,
    // status, verifiedAt) is already set above, and this keeps the mapping identical to
    // getProgressForUser's.
    return RoadmapProgressDto.from(progress);
  }

  /**
   * Records the points for one verified node, and clears the weaker award for the same node first.
   *
   * <p>The reputation ledger de-duplicates on {@code (userId, sourceType, sourceId)}. {@code
   * sourceId} here is the node, but the <em>tier</em> decides the source type — so a node that was
   * self-verified and later verified properly produced two ledger rows for one skill, and the user
   * banked 5 + 20 instead of 20. Revoking the self-verified entry before granting the real one
   * keeps one node worth one award, whichever route it took.
   *
   * <p>Revoking an award that was never granted is a no-op ({@code ReputationService#revoke}
   * deletes by the same triple), so this is safe on the common path where no self-verification
   * happened.
   */
  private void awardForNode(Integer userId, Integer nodeId, RepSourceType awardType) {
    String sourceId = progressSourceId(userId, nodeId);

    if (awardType == RepSourceType.ROADMAP_NODE_VERIFIED) {
      reputationEventPublisher.revoke(userId, RepSourceType.ROADMAP_SELF_VERIFIED, sourceId);
    }

    reputationEventPublisher.award(userId, awardType, sourceId);
  }

  /**
   * Refuses a re-submission that would let the claimant overwrite a decision already on the record.
   *
   * <p>{@code SELF_VERIFIED} is the tier with no reviewer: it writes {@code VERIFIED} immediately.
   * Without this guard it could be pointed at a row a moderator had already {@code REJECTED} and
   * flip it to verified — the person whose claim was refused erasing the refusal, and collecting
   * the self-verified points for doing it. Nothing recorded that the rejection had ever happened.
   *
   * <p>The rule is therefore: <b>{@code SELF_VERIFIED} may only be used on a node nobody has ruled
   * on yet.</b> Every other transition stays open, including re-submitting a rejected claim for
   * review with better proof, and upgrading a self-verified node to a moderator-reviewed one —
   * both of those go to {@code PENDING_APPROVAL} and are decided by somebody else.
   */
  private void requireResubmittable(
      UserRoadmapProgressEntity existing, VerificationTier requestedTier) {
    if (requestedTier != VerificationTier.SELF_VERIFIED) {
      return;
    }
    if (existing.getStatus() == VerificationStatus.VERIFIED) {
      throw new ValidationException("This skill is already verified");
    }
    if (existing.getStatus() == VerificationStatus.REJECTED) {
      throw new ValidationException(
          "This claim was reviewed and rejected. Submit it for review again with new proof rather"
              + " than self-verifying it.");
    }
    if (existing.getStatus() == VerificationStatus.PENDING_APPROVAL) {
      throw new ValidationException(
          "This claim is waiting on a reviewer. Wait for the decision rather than self-verifying"
              + " it.");
    }
  }

  /**
   * (userId, nodeId) rather than the progress row's generated id — stable regardless of whether
   * the id has been assigned by the DB yet, and already the natural unique key for this row
   * ({@code uq_user_node}).
   */
  private String progressSourceId(Integer userId, Integer nodeId) {
    return userId + ":" + nodeId;
  }

  /**
   * AUTO_CERTIFIED proof is accepted only when it points at a repository under the user's own
   * linked GitHub account — reusing the account already synced via the {@code github} module
   * rather than issuing a fresh GitHub API call per verification request. No linked account, no
   * proof URL, or a URL under someone else's username are all rejected.
   */
  private boolean verifyViaExternalApi(UserEntity user, String proofUrl) {
    if (proofUrl == null || proofUrl.isBlank()) {
      return false;
    }

    return githubStatsRepository
        .findByUserId(user.getId())
        .map(stats -> proofUrl.startsWith("https://github.com/" + stats.getGithubUsername() + "/"))
        .orElse(false);
  }

  @Transactional
  public void approveRequest(Integer progressId, Integer moderatorId) {
    UserRoadmapProgressEntity progress =
        progressRepository
            .findById(progressId)
            .orElseThrow(() -> new NotFoundException("Progress not found"));

    if (progress.getStatus() != VerificationStatus.PENDING_APPROVAL) {
      throw new ConflictException(
          "Cannot approve a verification request that is already " + progress.getStatus());
    }

    // Nobody signs off on their own claim, admin or not. Only admins reach this method (both
    // @PreAuthorize and a SecurityConfig matcher say so), so this is not a privilege check — it is
    // the same "no self-crediting" rule PostService#acceptAnswer and ProjectService#applyToPosition
    // apply, on the highest-value award in the system (ROADMAP_NODE_VERIFIED, 20 points). An admin
    // who wants the badge files the claim and another admin decides it.
    if (moderatorId.equals(progress.getUser().getId())) {
      throw new ForbiddenException("You cannot approve your own verification request");
    }

    UserEntity moderator =
        userRepository
            .findById(moderatorId)
            .orElseThrow(() -> new NotFoundException("Moderator not found"));

    progress.setStatus(VerificationStatus.VERIFIED);
    progress.setVerifier(moderator);
    progress.setVerifiedAt(OffsetDateTime.now());
    progressRepository.save(progress);

    awardForNode(
        progress.getUser().getId(),
        progress.getNode().getId(),
        RepSourceType.ROADMAP_NODE_VERIFIED);

    notifyDecision(progress, NotificationType.SKILL_VERIFIED);
  }

  @Transactional
  public void rejectRequest(Integer progressId, Integer moderatorId) {
    UserRoadmapProgressEntity progress =
        progressRepository
            .findById(progressId)
            .orElseThrow(() -> new NotFoundException("Progress not found"));

    if (progress.getStatus() != VerificationStatus.PENDING_APPROVAL) {
      throw new ConflictException(
          "Cannot reject a verification request that is already " + progress.getStatus());
    }

    UserEntity moderator =
        userRepository
            .findById(moderatorId)
            .orElseThrow(() -> new NotFoundException("Moderator not found"));

    progress.setStatus(VerificationStatus.REJECTED);
    progress.setVerifier(moderator);
    progress.setVerifiedAt(OffsetDateTime.now());
    progressRepository.save(progress);

    notifyDecision(progress, NotificationType.SKILL_REJECTED);
  }

  /**
   * Tells the claimant how their request went.
   *
   * <p><b>Only for the two moderator decisions</b>, not for {@code SELF_VERIFIED} or {@code
   * AUTO_CERTIFIED}. Those two are resolved inside the same request that submits them, so the
   * claimant is already looking at the answer; a notification there would be the system telling
   * someone what they just did. These two arrive minutes or days later, to someone who has left the
   * page — which is the whole reason the loop was ending in silence.
   *
   * <p><b>No {@code actorId}, deliberately.</b> The obvious value is the moderator's id, and it is
   * the one thing this must not carry: which moderator ruled on a claim is internal (see {@code
   * RoadmapProgressDto}), and {@code NotificationResponseDto} hands {@code actorId} straight to the
   * client. A null actor also skips the block check in {@code NotificationService.send}, which is
   * correct here — a moderation outcome is not a person acting on you, and letting a block swallow
   * it would leave the claim looking unanswered forever.
   *
   * <p>{@code referenceId} is the roadmap node, not the progress row: the node is what the client
   * can link to, and the progress row's id is an internal key with no route in front of it.
   */
  private void notifyDecision(UserRoadmapProgressEntity progress, NotificationType type) {
    boolean verified = NotificationType.SKILL_VERIFIED.equals(type);
    String skill = progress.getNode().getName();

    notificationService.send(
        SendNotificationRequest.builder()
            .recipientId(progress.getUser().getId())
            .type(type)
            .title(verified ? "Skill verified" : "Skill verification declined")
            .body(
                verified
                    ? "Your claim for \"" + skill + "\" was verified"
                    : "Your claim for \"" + skill + "\" was not verified")
            .messageKey(
                verified
                    ? NotificationMessages.SKILL_VERIFIED
                    : NotificationMessages.SKILL_REJECTED)
            .messageArgs(NotificationMessages.args("skill", skill))
            .referenceId(progress.getNode().getId())
            .referenceType("ROADMAP_NODE")
            .build());
  }

  @Transactional(readOnly = true)
  public List<PendingVerificationDto> getPendingRequests() {
    return progressRepository
        .findByStatusWithUserAndNode(VerificationStatus.PENDING_APPROVAL)
        .stream()
        .map(this::toPendingDto)
        .toList();
  }

  private PendingVerificationDto toPendingDto(UserRoadmapProgressEntity progress) {
    UserEntity requester = progress.getUser();
    RoadmapNodeEntity node = progress.getNode();
    return PendingVerificationDto.builder()
        .progressId(progress.getId())
        .userId(requester.getId())
        .username(requester.getUsername())
        .fullName(requester.getFullName())
        .profilePictureUrl(requester.getProfilePictureUrl())
        .nodeId(node.getId())
        .nodeName(node.getName())
        .tier(progress.getTier())
        .proofUrl(progress.getProofUrl())
        .proofImageKey(progress.getProofImageKey())
        .requestedAt(progress.getCreatedAt())
        .build();
  }
}
