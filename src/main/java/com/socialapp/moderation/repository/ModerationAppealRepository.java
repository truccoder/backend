package com.socialapp.moderation.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.socialapp.moderation.entity.ModerationAppealEntity;
import com.socialapp.moderation.enums.AppealStatus;

@Repository
public interface ModerationAppealRepository extends JpaRepository<ModerationAppealEntity, Long> {

  /**
   * The admin queue: oldest first, so an appeal cannot be starved by newer ones arriving. The
   * person on the other end of the oldest row has been waiting the longest.
   */
  Page<ModerationAppealEntity> findByStatusOrderByCreatedAtAsc(
      AppealStatus status, Pageable pageable);

  /** The user's own appeals, newest first. */
  List<ModerationAppealEntity> findByUserIdOrderByCreatedAtDesc(Integer userId);

  /**
   * Guards the "one open appeal per violation" rule before hitting the partial unique index from
   * {@code V49}.
   *
   * <p>The index is the real guarantee — this check races and cannot be relied on alone — but it
   * turns the common case (a user clicking submit twice) into a clear 400 instead of a constraint
   * violation surfacing as a 500.
   */
  boolean existsByViolationIdAndStatus(Long violationId, AppealStatus status);

  /**
   * The subset of {@code violationIds} that already have an appeal in {@code status}, in one
   * query.
   *
   * <p>For the violations list, which asked {@link #existsByViolationIdAndStatus} once per row —
   * a user with twenty violations cost twenty round trips to render one page.
   */
  @Query(
      "SELECT a.violationId FROM ModerationAppealEntity a "
          + "WHERE a.violationId IN :violationIds AND a.status = :status")
  List<Long> findViolationIdsWithStatus(
      @Param("violationIds") Collection<Long> violationIds, @Param("status") AppealStatus status);
}
