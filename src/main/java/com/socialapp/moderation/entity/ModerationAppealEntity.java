package com.socialapp.moderation.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.socialapp.moderation.enums.AppealStatus;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A user's challenge to one violation recorded against them.
 *
 * <p>Plain id columns rather than {@code @ManyToOne} associations, matching {@link
 * UserViolationEntity} and {@link UserBanEntity} in the same package: this module reads ids and
 * hydrates users deliberately, which keeps a moderation row from dragging a whole {@code
 * UserEntity} — password hash included — into a response by accident.
 */
@Entity
@Table(name = "t_moderation_appeals")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationAppealEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Integer userId;

  /**
   * Null once the appeal has been upheld: approving deletes the violation, and the FK is {@code ON
   * DELETE SET NULL} so the appeal itself survives its own success. See {@code V49}.
   */
  @Column(name = "violation_id")
  private Long violationId;

  @Column(nullable = false, length = 2000)
  private String reason;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  @Builder.Default
  private AppealStatus status = AppealStatus.PENDING;

  @Column(name = "reviewer_id")
  private Integer reviewerId;

  @Column(name = "reviewer_note", length = 2000)
  private String reviewerNote;

  @Column(name = "reviewed_at")
  private OffsetDateTime reviewedAt;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;
}
