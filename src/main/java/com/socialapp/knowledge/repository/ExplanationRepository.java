package com.socialapp.knowledge.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.socialapp.knowledge.entity.ExplanationEntity;

public interface ExplanationRepository extends JpaRepository<ExplanationEntity, Integer> {

  @Query(
      "SELECT e FROM ExplanationEntity e WHERE e.postId = :postId AND e.userId = :userId"
          + " ORDER BY e.version DESC LIMIT 1")
  Optional<ExplanationEntity> findLatestByPostAndUser(Integer postId, Integer userId);

  List<ExplanationEntity> findByUserIdOrderByCreatedAtDesc(Integer userId);

  @Query(
      "SELECT e FROM ExplanationEntity e WHERE e.userId = :userId"
          + " AND e.updatedAt > :since ORDER BY e.updatedAt DESC")
  List<ExplanationEntity> findByUserIdUpdatedAfter(Integer userId, OffsetDateTime since);

  @Query(
      "SELECT MAX(e.version) FROM ExplanationEntity e WHERE e.postId = :postId AND e.userId = :userId")
  Optional<Integer> findMaxVersion(Integer postId, Integer userId);
}
