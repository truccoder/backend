package com.socialapp.roadmap.dto;

import java.time.OffsetDateTime;

import com.socialapp.roadmap.entity.UserRoadmapProgressEntity;
import com.socialapp.roadmap.enums.VerificationStatus;
import com.socialapp.roadmap.enums.VerificationTier;

import lombok.Builder;
import lombok.Data;

/**
 * One skill on a user's roadmap, as the "verified skills" card on a public profile shows it.
 *
 * <p><b>What is deliberately absent matters more than what is here.</b> This endpoint is readable
 * by strangers and by signed-out visitors, so the DTO carries no {@code proofUrl} or {@code
 * proofImageKey} (a personal link — a private repo, a company certificate, a scan of a document
 * — submitted to a moderator, not published), no verifier identity (which moderator approved a
 * skill is internal), and nothing off the {@code UserEntity}. Compare {@link
 * PendingVerificationDto}, which does carry the requester's identity because only moderators read
 * it.
 */
@Data
@Builder
public class RoadmapProgressDto {
  private Integer nodeId;
  private String nodeName;
  private VerificationTier tier;
  private VerificationStatus status;
  private OffsetDateTime verifiedAt;

  /** Reads {@code node}, which is lazy — call inside a transaction or with it join-fetched. */
  public static RoadmapProgressDto from(UserRoadmapProgressEntity progress) {
    return RoadmapProgressDto.builder()
        .nodeId(progress.getNode().getId())
        .nodeName(progress.getNode().getName())
        .tier(progress.getTier())
        .status(progress.getStatus())
        .verifiedAt(progress.getVerifiedAt())
        .build();
  }
}
