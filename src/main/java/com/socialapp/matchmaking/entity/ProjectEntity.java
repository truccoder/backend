package com.socialapp.matchmaking.entity;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

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

  /**
   * What the project is <em>about</em>, as opposed to what it needs built — the roles carry the
   * skills. Fed to {@code MatchmakingService.suggestProjects}, where it is crossed with a user's
   * {@code interestedDomains}; see {@code V74__add_tags_to_projects.sql} for why this is a column
   * rather than something inferred from the description.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private List<String> tags;

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
