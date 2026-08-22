package com.socialapp.moderation.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.socialapp.moderation.entity.PostReportEntity;

@Repository
public interface PostReportRepository extends JpaRepository<PostReportEntity, Integer> {

  boolean existsByPostIdAndReporterId(Integer postId, Integer reporterId);

  /**
   * How many <b>people</b> have reported this post.
   *
   * <p>{@code COUNT(*)} would say the same thing today because {@code uq_post_reports_post_reporter}
   * already makes one row per person, but the count is what the escalation threshold is compared
   * against, and stating "distinct reporters" here means the threshold keeps meaning that even if
   * the constraint is ever relaxed to allow a second report with a different reason.
   */
  @Query("SELECT COUNT(DISTINCT r.reporterId) FROM PostReportEntity r WHERE r.postId = :postId")
  long countDistinctReporters(@Param("postId") Integer postId);

  Page<PostReportEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

  Page<PostReportEntity> findByPostIdOrderByCreatedAtDesc(Integer postId, Pageable pageable);

  List<PostReportEntity> findByPostIdInOrderByCreatedAtDesc(List<Integer> postIds);
}
