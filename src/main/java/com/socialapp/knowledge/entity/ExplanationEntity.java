package com.socialapp.knowledge.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "t_explanations")
@Data
@NoArgsConstructor
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
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private List<String> concepts;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private List<String> prerequisites;

  private Integer complexityScore;

  @Column(columnDefinition = "TEXT")
  private String feedbackNote;

  private Integer version;

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;

  @Builder
  public ExplanationEntity(
      Integer id,
      Integer postId,
      Integer userId,
      String originalContent,
      String explanationContent,
      List<String> concepts,
      List<String> prerequisites,
      Integer complexityScore,
      String feedbackNote,
      Integer version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.postId = postId;
    this.userId = userId;
    this.originalContent = originalContent;
    this.explanationContent = explanationContent;
    this.concepts = concepts == null ? null : new ArrayList<>(concepts);
    this.prerequisites = prerequisites == null ? null : new ArrayList<>(prerequisites);
    this.complexityScore = complexityScore;
    this.feedbackNote = feedbackNote;
    // No @Builder.Default here: it's incompatible with a constructor-level @Builder (Lombok
    // compile error). This plain null-coalesce achieves the same "unset -> 1" behavior for the
    // common .builder().build() case; it only differs from real @Builder.Default in that an
    // explicit .version(null) would also become 1 instead of staying null — nothing in this
    // codebase relies on that distinction (confirmed: every call site passes a concrete value).
    this.version = version == null ? 1 : version;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public List<String> getConcepts() {
    return concepts == null ? List.of() : List.copyOf(concepts);
  }

  public void setConcepts(List<String> concepts) {
    this.concepts = concepts == null ? null : new ArrayList<>(concepts);
  }

  public List<String> getPrerequisites() {
    return prerequisites == null ? List.of() : List.copyOf(prerequisites);
  }

  public void setPrerequisites(List<String> prerequisites) {
    this.prerequisites = prerequisites == null ? null : new ArrayList<>(prerequisites);
  }
}
