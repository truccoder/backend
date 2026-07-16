package com.socialapp.github.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.socialapp.github.entity.GithubStatsEntity;

@Repository
public interface GithubStatsRepository extends JpaRepository<GithubStatsEntity, Integer> {
  Optional<GithubStatsEntity> findByUserId(Integer userId);

  @Query(
      "SELECT g FROM GithubStatsEntity g WHERE g.lastSyncedAt < :threshold OR g.lastSyncedAt IS NULL ORDER BY g.lastSyncedAt ASC NULLS FIRST")
  List<GithubStatsEntity> findUsersToSync(
      @Param("threshold") OffsetDateTime threshold, Pageable pageable);
}
