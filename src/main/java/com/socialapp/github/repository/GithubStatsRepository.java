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

  /**
   * Rows the background sync should pick up: stale (or never synced) <b>and</b> still holding a
   * token.
   *
   * <p>The {@code accessToken IS NOT NULL} clause is not defensive padding. A row without a token
   * can never sync — {@code GithubService#performSync} throws on it — so returning it means the
   * scheduler burns one of its 15 slots per minute on a row that fails, logs an error, and comes
   * back stale in the very next run. Seeded rows and rows whose token was cleared would produce
   * that error every 60 seconds forever.
   */
  @Query(
      "SELECT g FROM GithubStatsEntity g WHERE g.accessToken IS NOT NULL AND (g.lastSyncedAt < :threshold OR g.lastSyncedAt IS NULL) ORDER BY g.lastSyncedAt ASC NULLS FIRST")
  List<GithubStatsEntity> findUsersToSync(
      @Param("threshold") OffsetDateTime threshold, Pageable pageable);
}
