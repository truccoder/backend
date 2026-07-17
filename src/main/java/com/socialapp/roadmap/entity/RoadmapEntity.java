package com.socialapp.roadmap.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "t_roadmaps")
@Data
@NoArgsConstructor
public class RoadmapEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @Column(nullable = false)
  private String name;

  @Column(columnDefinition = "TEXT")
  private String description;

  @OneToMany(mappedBy = "roadmap", cascade = CascadeType.ALL, orphanRemoval = true)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private List<RoadmapNodeEntity> nodes;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  @Builder
  public RoadmapEntity(
      Integer id,
      String name,
      String description,
      List<RoadmapNodeEntity> nodes,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.name = name;
    this.description = description;
    this.nodes = nodes == null ? null : new ArrayList<>(nodes);
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public List<RoadmapNodeEntity> getNodes() {
    return nodes == null ? List.of() : List.copyOf(nodes);
  }

  public void setNodes(List<RoadmapNodeEntity> nodes) {
    this.nodes = nodes == null ? null : new ArrayList<>(nodes);
  }
}
