package com.socialapp.matchmaking.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.matchmaking.entity.ProjectEntity;

public interface ProjectRepository extends JpaRepository<ProjectEntity, Integer> {

  /**
   * One cursor page of projects, newest first.
   *
   * <p>{@code JOIN FETCH p.author} because every card shows the owner's name and avatar; without
   * it a page of 10 projects is 11 queries. The positions are deliberately <em>not</em> fetched
   * here — a second join fetch across two collections multiplies the row count, and {@code
   * ProjectQueryService} loads them for the whole page in one separate query instead.
   *
   * <p>Cursor is the id, descending; see {@code ProjectPageResponseDto}.
   */
  @Query(
      """
      SELECT p FROM ProjectEntity p
      JOIN FETCH p.author
      WHERE (:cursor IS NULL OR p.id < :cursor)
      ORDER BY p.id DESC
      """)
  List<ProjectEntity> findPage(@Param("cursor") Integer cursor, Pageable pageable);

  /**
   * The pool that {@code MatchmakingService.suggestProjects} ranks: projects a given user could
   * plausibly join today.
   *
   * <p>Three conditions, each of which removes a project it would be wrong to suggest:
   *
   * <ul>
   *   <li>the project is {@code OPEN} — a closed project is not recruiting;
   *   <li>the caller is not its author — you cannot apply to your own project ({@code
   *       ProjectService.applyToPosition} refuses it outright), so suggesting it is a dead end;
   *   <li>at least one position is still {@code OPEN} — a project whose every role is filled has
   *       nothing to apply to, and the ranking would happily float it to the top on skill overlap.
   * </ul>
   *
   * <p>{@code JOIN FETCH p.author} for the same reason as {@link #findPage}: every card shows the
   * owner. Positions are again left out and loaded for the whole pool in one query by the caller.
   *
   * <p>Ordered by id descending and bounded by {@code pageable} so the pool is the newest N
   * candidates. Scoring then reorders within that window — this is a pool, not the answer.
   */
  @Query(
      """
      SELECT p FROM ProjectEntity p
      JOIN FETCH p.author
      WHERE p.status = com.socialapp.matchmaking.entity.enums.ProjectStatus.OPEN
        AND p.author.id <> :callerId
        AND EXISTS (
            SELECT 1 FROM ProjectPositionEntity pos
            WHERE pos.project = p
              AND pos.status = com.socialapp.matchmaking.entity.enums.PositionStatus.OPEN)
      ORDER BY p.id DESC
      """)
  List<ProjectEntity> findOpenProjectsForMatching(
      @Param("callerId") Integer callerId, Pageable pageable);

  /** One project with its owner already loaded, for the detail screen. */
  @Query("SELECT p FROM ProjectEntity p JOIN FETCH p.author WHERE p.id = :id")
  Optional<ProjectEntity> findByIdWithAuthor(@Param("id") Integer id);
}
