package com.socialapp.matchmaking.entity;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.socialapp.matchmaking.entity.enums.ProjectStatus;
import com.socialapp.security.entity.UserEntity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(name = "t_projects")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "author_id", nullable = false)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private UserEntity author;

  private String title;

  private String description;

  private String bannerUrl;

  @Enumerated(EnumType.STRING)
  private ProjectStatus status = ProjectStatus.OPEN;

  @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private List<ProjectPositionEntity> positions;

  @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private List<ProjectApplicationEntity> applications;

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;
}
