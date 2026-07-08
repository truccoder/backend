package com.socialapp.moderation.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.socialapp.moderation.entity.ModerationLogEntity;
import com.socialapp.moderation.enums.ModerationStatus;

@Repository
public interface ModerationLogRepository extends JpaRepository<ModerationLogEntity, Long> {
  List<ModerationLogEntity> findByPostId(Integer postId);

  List<ModerationLogEntity> findByPostIdOrderByCreatedAtAsc(Integer postId);

  List<ModerationLogEntity> findByStatus(ModerationStatus status);

  @Query(
      """
                      SELECT l FROM ModerationLogEntity l
                      WHERE (:postId IS NULL OR l.postId = :postId)
                        AND (:status IS NULL OR l.status = :status)
                        AND (:authorId IS NULL OR l.postId IN (
                              SELECT p.id FROM PostEntity p WHERE p.authorId = :authorId))
                      ORDER BY l.createdAt DESC
                    """)
  Page<ModerationLogEntity> search(
      @Param("postId") Integer postId,
      @Param("authorId") Integer authorId,
      @Param("status") ModerationStatus status,
      Pageable pageable);
}
