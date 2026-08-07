package com.socialapp.matchmaking.dto;

import java.util.List;

import com.socialapp.matchmaking.entity.ProjectPositionEntity;
import com.socialapp.matchmaking.entity.enums.PositionStatus;

import lombok.Builder;
import lombok.Data;

/** One open (or filled) role on a project, as the browse and detail screens show it. */
@Data
@Builder
public class ProjectPositionResponseDto {
  private Integer id;
  private String title;
  private String description;
  private List<String> requiredSkills;
  private Integer quantity;
  private PositionStatus status;

  /**
   * Maps inside a transaction only — {@code requiredSkills} is a jsonb column on the entity, which
   * is eager, but the entity itself may still be a proxy when this is called from a lazily loaded
   * collection.
   */
  public static ProjectPositionResponseDto from(ProjectPositionEntity position) {
    return ProjectPositionResponseDto.builder()
        .id(position.getId())
        .title(position.getTitle())
        .description(position.getDescription())
        .requiredSkills(position.getRequiredSkills())
        .quantity(position.getQuantity())
        .status(position.getStatus())
        .build();
  }
}
