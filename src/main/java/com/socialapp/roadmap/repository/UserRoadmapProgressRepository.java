package com.socialapp.roadmap.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.socialapp.roadmap.entity.UserRoadmapProgressEntity;
import com.socialapp.roadmap.enums.VerificationStatus;

@Repository
public interface UserRoadmapProgressRepository
    extends JpaRepository<UserRoadmapProgressEntity, Integer> {
  List<UserRoadmapProgressEntity> findByUserId(Integer userId);

  List<UserRoadmapProgressEntity> findByStatus(VerificationStatus status);

  Optional<UserRoadmapProgressEntity> findByUserIdAndNodeId(Integer userId, Integer nodeId);

  boolean existsByUserIdAndStatus(Integer userId, VerificationStatus status);
}
