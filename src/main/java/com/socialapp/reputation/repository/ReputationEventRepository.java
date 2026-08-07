package com.socialapp.reputation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.reputation.entity.ReputationEventEntity;
import com.socialapp.reputation.entity.enums.RepSourceType;

public interface ReputationEventRepository extends JpaRepository<ReputationEventEntity, Long> {

  /**
   * Idempotent award: relies on the DB unique constraint rather than a check-then-act, so
   * concurrent retries of the same signal can never double-award. Returns the number of rows
   * inserted (0 if the event already existed).
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO socialapp.t_reputation_events (user_id, source_type, source_id, points) "
              + "VALUES (:userId, :sourceType, :sourceId, :points) "
              + "ON CONFLICT (user_id, source_type, source_id) DO NOTHING",
      nativeQuery = true)
  int insertIfAbsent(
      @Param("userId") Integer userId,
      @Param("sourceType") String sourceType,
      @Param("sourceId") String sourceId,
      @Param("points") Integer points);

  /** Returns the number of rows deleted (0 if the event never existed). */
  @Modifying
  @Query(
      "DELETE FROM ReputationEventEntity e "
          + "WHERE e.userId = :userId AND e.sourceType = :sourceType AND e.sourceId = :sourceId")
  int deleteByUserIdAndSourceTypeAndSourceId(
      @Param("userId") Integer userId,
      @Param("sourceType") RepSourceType sourceType,
      @Param("sourceId") String sourceId);

  @Query("SELECT COALESCE(SUM(e.points), 0) FROM ReputationEventEntity e WHERE e.userId = :userId")
  int sumPointsByUserId(@Param("userId") Integer userId);

  @Query("SELECT DISTINCT e.userId FROM ReputationEventEntity e")
  List<Integer> findDistinctUserIds();
}
