package com.socialapp.matchmaking.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.socialapp.matchmaking.entity.enums.ApplicationStatus;
import com.socialapp.security.entity.UserEntity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(name = "t_project_applications")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectApplicationEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "project_id", nullable = false)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private ProjectEntity project;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "position_id", nullable = false)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private ProjectPositionEntity position;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "applicant_id", nullable = false)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private UserEntity applicant;

  private String message;

  @Enumerated(EnumType.STRING)
  private ApplicationStatus status = ApplicationStatus.PENDING;

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;
}
