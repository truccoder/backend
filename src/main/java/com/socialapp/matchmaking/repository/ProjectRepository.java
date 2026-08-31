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

  /**
   * Ids of projects matching a free-text query, newest first — backend-plan B33.
   *
   * <p>{@code /v1/api/search} covered people, posts and books and nothing else, so the frontend's
   * "Dự án" tab had to pull the project board and filter it client-side, seeing only the newest
   * ~200. This is the server-side branch behind it.
   *
   * <p>Matches four places, all diacritics-insensitive substring on {@code f_unaccent(lower(...))}
   * — the same shape as {@code PostRepository.searchByContentOrEventName}, so {@code
   * V100} adds the trigram GIN indexes for {@code title}/{@code description} that V48 gave the
   * other tables:
   *
   * <ul>
   *   <li>{@code title} and {@code description} on the project;
   *   <li>any string in the project's {@code tags} jsonb array;
   *   <li>any string in any position's {@code required_skills} jsonb array — the column {@code
   *       ProfileMatchScorer} ranks candidates on and the one the frontend shows as
   *       "Java · Spring Boot" on a suggested-project card, previously invisible to search.
   * </ul>
   *
   * <p>Native because JPQL cannot unnest a jsonb array. {@code coalesce(..., '[]'::jsonb)} guards
   * the nullable {@code tags}/{@code required_skills} columns — {@code jsonb_array_elements_text}
   * errors on a SQL null. The pattern is expected pre-escaped for {@code LIKE} (the caller runs it
   * through {@code SearchQuerySanitizer}); {@code ESCAPE '\'} then makes a literal {@code %} match
   * a {@code %} rather than everything. Returns ids, not entities, so the author can be join-fetched
   * in a second step instead of lazy-loaded per row.
   */
  @Query(
      value =
          """
          SELECT p.id FROM socialapp.t_projects p
          WHERE f_unaccent(lower(p.title))
                  LIKE f_unaccent(lower(concat('%', :query, '%'))) ESCAPE '\\'
             OR f_unaccent(lower(coalesce(p.description, '')))
                  LIKE f_unaccent(lower(concat('%', :query, '%'))) ESCAPE '\\'
             OR EXISTS (
                  SELECT 1
                  FROM jsonb_array_elements_text(coalesce(p.tags, cast('[]' as jsonb))) AS tag
                  WHERE f_unaccent(lower(tag))
                          LIKE f_unaccent(lower(concat('%', :query, '%'))) ESCAPE '\\')
             OR EXISTS (
                  SELECT 1 FROM socialapp.t_project_positions pos
                  WHERE pos.project_id = p.id
                    AND EXISTS (
                        SELECT 1
                        FROM jsonb_array_elements_text(
                               coalesce(pos.required_skills, cast('[]' as jsonb))) AS skill
                        WHERE f_unaccent(lower(skill))
                                LIKE f_unaccent(lower(concat('%', :query, '%'))) ESCAPE '\\'))
          ORDER BY p.id DESC
          LIMIT :size
          """,
      nativeQuery = true)
  List<Integer> searchIds(@Param("query") String query, @Param("size") int size);

  /**
   * The given projects with their owners loaded, for mapping outside a lazy-safe context.
   *
   * <p>{@code IN} does not preserve order, so the caller re-imposes the id order it asked for.
   */
  @Query("SELECT p FROM ProjectEntity p JOIN FETCH p.author WHERE p.id IN :ids")
  List<ProjectEntity> findAllByIdWithAuthor(@Param("ids") List<Integer> ids);
}
