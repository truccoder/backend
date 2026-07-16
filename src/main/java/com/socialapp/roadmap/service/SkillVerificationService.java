package com.socialapp.roadmap.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

  @Transactional
  public void submitVerificationRequest(Integer userId, SkillVerificationRequestDto dto) {
    UserEntity user =
        userRepository.findById(userId).orElseThrow(() -> new RuntimeException("User not found"));
    RoadmapNodeEntity node =
        nodeRepository
            .findById(dto.getNodeId())
            .orElseThrow(() -> new RuntimeException("Node not found"));

    Optional<UserRoadmapProgressEntity> existingProgress =
        progressRepository.findByUserIdAndNodeId(userId, dto.getNodeId());

    UserRoadmapProgressEntity progress =
        existingProgress.orElseGet(
            () -> UserRoadmapProgressEntity.builder().user(user).node(node).build());

    progress.setTier(dto.getTier());
    progress.setProofUrl(dto.getProofUrl());
    progress.setProofImageKey(dto.getProofImageKey());

    if (dto.getTier() == VerificationTier.SELF_VERIFIED) {
      progress.setStatus(VerificationStatus.VERIFIED);
      progress.setVerifiedAt(OffsetDateTime.now());
    } else if (dto.getTier() == VerificationTier.MOD_VERIFIED
        || dto.getTier() == VerificationTier.QUIZ_VERIFIED) {
      progress.setStatus(VerificationStatus.PENDING_APPROVAL);
    } else if (dto.getTier() == VerificationTier.AUTO_CERTIFIED) {
      // TODO: Implement external API call (GitHub/Credly) using the user's OAuth token
      // For now, assume it's pending a background job or synchronously verified
      boolean isValid = verifyViaExternalApi(user, dto.getProofUrl());
      if (isValid) {
        progress.setStatus(VerificationStatus.VERIFIED);
        progress.setVerifiedAt(OffsetDateTime.now());
      } else {
        progress.setStatus(VerificationStatus.REJECTED);
      }
    }

    progressRepository.save(progress);
  }

  private boolean verifyViaExternalApi(UserEntity user, String proofUrl) {
    // Logic to use GitHub OAuth token or Credly API
    // E.g., fetch repositories and check if proofUrl is among them
    return true; // Mock true for now
  }

  @Transactional
  public void approveRequest(Integer progressId, Integer moderatorId) {
    UserRoadmapProgressEntity progress =
        progressRepository
            .findById(progressId)
            .orElseThrow(() -> new RuntimeException("Progress not found"));
    UserEntity moderator =
        userRepository
            .findById(moderatorId)
            .orElseThrow(() -> new RuntimeException("Moderator not found"));

    progress.setStatus(VerificationStatus.VERIFIED);
    progress.setVerifier(moderator);
    progress.setVerifiedAt(OffsetDateTime.now());
    progressRepository.save(progress);
  }

  @Transactional
  public void rejectRequest(Integer progressId, Integer moderatorId) {
    UserRoadmapProgressEntity progress =
        progressRepository
            .findById(progressId)
            .orElseThrow(() -> new RuntimeException("Progress not found"));
    UserEntity moderator =
        userRepository
            .findById(moderatorId)
            .orElseThrow(() -> new RuntimeException("Moderator not found"));

    progress.setStatus(VerificationStatus.REJECTED);
    progress.setVerifier(moderator);
    progress.setVerifiedAt(OffsetDateTime.now());
    progressRepository.save(progress);
  }

  public List<UserRoadmapProgressEntity> getPendingRequests() {
    return progressRepository.findByStatus(VerificationStatus.PENDING_APPROVAL);
  }
}
