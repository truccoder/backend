package com.socialapp.matchmaking.entity;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.socialapp.matchmaking.entity.enums.PositionStatus;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(name = "t_project_positions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectPositionEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "project_id", nullable = false)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private ProjectEntity project;

  private String title;

  private String description;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private List<String> requiredSkills;

  private Integer quantity = 1;

  @Enumerated(EnumType.STRING)
  private PositionStatus status = PositionStatus.OPEN;

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;
}
