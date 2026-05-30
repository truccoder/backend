package com.socialapp.moderation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.socialapp.moderation.entity.ModerationLogEntity;
import com.socialapp.moderation.enums.ModerationStatus;

@Repository
public interface ModerationLogRepository extends JpaRepository<ModerationLogEntity, Long> {
  List<ModerationLogEntity> findByPostId(Integer postId);

  List<ModerationLogEntity> findByStatus(ModerationStatus status);
}
