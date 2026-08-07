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

  /** One project with its owner already loaded, for the detail screen. */
  @Query("SELECT p FROM ProjectEntity p JOIN FETCH p.author WHERE p.id = :id")
  Optional<ProjectEntity> findByIdWithAuthor(@Param("id") Integer id);
}
