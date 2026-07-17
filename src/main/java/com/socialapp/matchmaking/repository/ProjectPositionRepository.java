package com.socialapp.matchmaking.repository;

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
}
