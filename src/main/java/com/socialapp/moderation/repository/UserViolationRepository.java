package com.socialapp.moderation.repository;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.socialapp.moderation.entity.UserViolationEntity;

@Repository
public interface UserViolationRepository extends JpaRepository<UserViolationEntity, Long> {
  List<UserViolationEntity> findByUserIdOrderByCreatedAtDesc(Integer userId);

  long countByUserId(Integer userId);

  @Query(
      """
        SELECT COUNT(v)
        FROM UserViolationEntity v
        WHERE v.userId = :userId AND v.createdAt > :since
      """)
  long countRecentViolations(@Param("userId") Integer userId, @Param("since") OffsetDateTime since);
}
