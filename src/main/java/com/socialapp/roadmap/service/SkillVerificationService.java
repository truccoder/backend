package com.socialapp.roadmap.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.github.repository.GithubStatsRepository;
import com.socialapp.reputation.entity.enums.RepSourceType;
import com.socialapp.reputation.event.ReputationEventPublisher;
import com.socialapp.roadmap.dto.PendingVerificationDto;
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

  @Transactional
  public void submitVerificationRequest(Integer userId, SkillVerificationRequestDto dto) {
    UserEntity user =
        userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    RoadmapNodeEntity node =
        nodeRepository
            .findById(dto.getNodeId())
            .orElseThrow(() -> new NotFoundException("Node not found"));

    Optional<UserRoadmapProgressEntity> existingProgress =
        progressRepository.findByUserIdAndNodeId(userId, dto.getNodeId());

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
      reputationEventPublisher.award(userId, awardType, progressSourceId(userId, dto.getNodeId()));
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
      throw new IllegalStateException(
          "Cannot approve a verification request that is already " + progress.getStatus());
    }

    UserEntity moderator =
        userRepository
            .findById(moderatorId)
            .orElseThrow(() -> new NotFoundException("Moderator not found"));

    progress.setStatus(VerificationStatus.VERIFIED);
    progress.setVerifier(moderator);
    progress.setVerifiedAt(OffsetDateTime.now());
    progressRepository.save(progress);

    reputationEventPublisher.award(
        progress.getUser().getId(),
        RepSourceType.ROADMAP_NODE_VERIFIED,
        progressSourceId(progress.getUser().getId(), progress.getNode().getId()));
  }

  @Transactional
  public void rejectRequest(Integer progressId, Integer moderatorId) {
    UserRoadmapProgressEntity progress =
        progressRepository
            .findById(progressId)
            .orElseThrow(() -> new NotFoundException("Progress not found"));

    if (progress.getStatus() != VerificationStatus.PENDING_APPROVAL) {
      throw new IllegalStateException(
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
