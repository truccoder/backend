package com.socialapp.moderation.entity;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.enums.ViolationType;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_moderation_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationLogEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Integer postId;

  @Enumerated(EnumType.STRING)
  private ModerationStatus status;

  @Enumerated(EnumType.STRING)
  private ViolationType violationType;

  private Double textToxicityScore;

  private Double imageSafeScore;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private List<String> ruleViolations;

  private OffsetDateTime reviewedAt;

  @CreationTimestamp private OffsetDateTime createdAt;
}
