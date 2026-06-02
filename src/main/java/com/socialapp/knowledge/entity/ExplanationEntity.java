package com.socialapp.knowledge.entity;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_explanations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExplanationEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "explanations_seq_gen")
  @SequenceGenerator(
      name = "explanations_seq_gen",
      sequenceName = "q_explanations_id",
      allocationSize = 1)
  private Integer id;

  private Integer postId;

  private Integer userId;

  @Column(columnDefinition = "TEXT")
  private String originalContent;

  @Column(columnDefinition = "TEXT")
  private String explanationContent;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private List<String> concepts;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private List<String> prerequisites;

  private Integer complexityScore;

  @Column(columnDefinition = "TEXT")
  private String feedbackNote;

  @Builder.Default private Integer version = 1;

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;
}
