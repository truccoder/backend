package com.socialapp.reputation.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.socialapp.reputation.entity.enums.RepSourceType;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Append-only ledger entry for one Elite Score award. The unique constraint on (userId,
 * sourceType, sourceId) is the idempotency guard — awarding the same signal twice is a no-op at
 * the database level rather than an application-side existence check.
 */
@Entity
@Table(name = "t_reputation_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReputationEventEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Integer userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false)
  private RepSourceType sourceType;

  @Column(name = "source_id", nullable = false)
  private String sourceId;

  @Column(nullable = false)
  private Integer points;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;
}
