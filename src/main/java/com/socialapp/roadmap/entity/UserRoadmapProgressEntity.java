package com.socialapp.roadmap.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.socialapp.roadmap.enums.VerificationStatus;
import com.socialapp.roadmap.enums.VerificationTier;
import com.socialapp.security.entity.UserEntity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "t_user_roadmap_progress",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"user_id", "node_id"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRoadmapProgressEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private UserEntity user;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "node_id", nullable = false)
  private RoadmapNodeEntity node;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private VerificationTier tier;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  @Builder.Default
  private VerificationStatus status = VerificationStatus.PENDING_APPROVAL;

  @Column(name = "proof_url", columnDefinition = "TEXT")
  private String proofUrl;

  @Column(name = "proof_image_key", columnDefinition = "TEXT")
  private String proofImageKey;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "verifier_id")
  private UserEntity verifier;

  @Column(name = "verified_at")
  private OffsetDateTime verifiedAt;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;
}
