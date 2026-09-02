package com.socialapp.matchmaking.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.matchmaking.entity.ProjectPositionEntity;

import jakarta.persistence.LockModeType;

public interface ProjectPositionRepository extends JpaRepository<ProjectPositionEntity, Integer> {

  /**
   * Locks the position row for the duration of the transaction so concurrent {@code
   * acceptApplication} calls for the same position serialize instead of each computing the
   * accepted-application count against a stale snapshot.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from ProjectPositionEntity p where p.id = :id")
  Optional<ProjectPositionEntity> findByIdForUpdate(@Param("id") Integer id);

  /**
   * One position with its project and the project's author loaded — what every owner-scoped
   * position operation (edit, delete, change status) needs to run its ownership check without a
   * lazy load, {@code open-in-view} being off.
   */
  @Query(
      """
      SELECT p FROM ProjectPositionEntity p
      JOIN FETCH p.project pr
      JOIN FETCH pr.author
      WHERE p.id = :id
      """)
  Optional<ProjectPositionEntity> findByIdWithProjectAuthor(@Param("id") Integer id);

  /**
   * Every position belonging to any of {@code projectIds}, in one query.
   *
   * <p>This is what keeps the project list at two queries instead of one per project: the caller
   * loads a page of projects, then all of that page's positions here, then groups them in memory.
   */
  @Query(
      """
      SELECT p FROM ProjectPositionEntity p
      WHERE p.project.id IN :projectIds
      ORDER BY p.id ASC
      """)
  List<ProjectPositionEntity> findByProjectIdIn(@Param("projectIds") List<Integer> projectIds);
}
