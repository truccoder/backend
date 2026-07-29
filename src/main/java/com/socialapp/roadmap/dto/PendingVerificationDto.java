package com.socialapp.roadmap.dto;

import java.time.OffsetDateTime;

import com.socialapp.roadmap.enums.VerificationTier;

import lombok.Builder;
import lombok.Data;

/**
 * Moderator-facing view of a verification request awaiting approval. Replaces the raw {@code
 * UserRoadmapProgressEntity} this endpoint used to return, which dragged the requester's whole
 * {@code UserEntity} — bcrypt hash included — into the response body.
 */
@Data
@Builder
public class PendingVerificationDto {
  private Integer progressId;

  private Integer userId;
  private String username;
  private String fullName;
  private String profilePictureUrl;

  private Integer nodeId;
  private String nodeName;

  private VerificationTier tier;
  private String proofUrl;
  private String proofImageKey;

  private OffsetDateTime requestedAt;
}
