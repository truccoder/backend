package com.socialapp.moderation.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.enums.ViolationType;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "t_moderation_logs")
@Data
@NoArgsConstructor
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
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private List<String> ruleViolations;

  private OffsetDateTime reviewedAt;

  @CreationTimestamp private OffsetDateTime createdAt;

  @Builder
  public ModerationLogEntity(
      Long id,
      Integer postId,
      ModerationStatus status,
      ViolationType violationType,
      Double textToxicityScore,
      Double imageSafeScore,
      List<String> ruleViolations,
      OffsetDateTime reviewedAt,
      OffsetDateTime createdAt) {
    this.id = id;
    this.postId = postId;
    this.status = status;
    this.violationType = violationType;
    this.textToxicityScore = textToxicityScore;
    this.imageSafeScore = imageSafeScore;
    this.ruleViolations = ruleViolations == null ? null : new ArrayList<>(ruleViolations);
    this.reviewedAt = reviewedAt;
    this.createdAt = createdAt;
  }

  public List<String> getRuleViolations() {
    return ruleViolations == null ? List.of() : List.copyOf(ruleViolations);
  }

  public void setRuleViolations(List<String> ruleViolations) {
    this.ruleViolations = ruleViolations == null ? null : new ArrayList<>(ruleViolations);
  }
}
