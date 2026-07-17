package com.socialapp.matchmaking.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.matchmaking.entity.ProjectApplicationEntity;
import com.socialapp.matchmaking.entity.enums.ApplicationStatus;

public interface ProjectApplicationRepository
    extends JpaRepository<ProjectApplicationEntity, Integer> {
  long countByPositionIdAndStatus(Integer positionId, ApplicationStatus status);
}
