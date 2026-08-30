package com.socialapp.knowledge.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.knowledge.entity.VaultNoteEntity;

public interface VaultNoteRepository extends JpaRepository<VaultNoteEntity, Integer> {

  Optional<VaultNoteEntity> findByUserIdAndFilename(Integer userId, String filename);

  /**
   * The caller's notes matching any of {@code filenames}, in one query.
   *
   * <p>The vault push looked each note up by filename inside its loop, so syncing a 500-note vault
   * cost 500 selects before the 500 saves. The list is client-supplied and unbounded.
   */
  List<VaultNoteEntity> findByUserIdAndFilenameIn(Integer userId, Collection<String> filenames);

  List<VaultNoteEntity> findByUserId(Integer userId);

  /**
   * The caller's notes, most recently edited first.
   *
   * <p><b>THE ORDERING IS THE POINT.</b> {@code loadVaultContext} takes the first 50 rows of an
   * unordered query and calls them the reader's knowledge — which made "which 50 notes does the AI
   * see?" a question only Postgres could answer, and it could answer it differently after a
   * vacuum. Most-recently-edited is the ordering the callers already assumed they had.
   */
  List<VaultNoteEntity> findByUserIdOrderByUpdatedAtDesc(Integer userId);

  /** One page of the owner's notes, newest id first — see {@code VaultNotePageResponseDto}. */
  @Query(
      "SELECT v FROM VaultNoteEntity v WHERE v.userId = :userId"
          + " AND (:cursor IS NULL OR v.id < :cursor) ORDER BY v.id DESC")
  List<VaultNoteEntity> findPage(
      @Param("userId") Integer userId, @Param("cursor") Integer cursor, Pageable pageable);

  Optional<VaultNoteEntity> findByIdAndUserId(Integer id, Integer userId);

  long countByUserId(Integer userId);

  /**
   * Wipe every note this user has ever synced.
   *
   * <p>A bulk delete rather than {@code deleteAll(findByUserId(...))}: the row count is the size of
   * somebody's vault, and loading all of it into the persistence context to delete it is the one
   * shape of this operation that can run the server out of memory.
   */
  @Modifying
  @Query("DELETE FROM VaultNoteEntity v WHERE v.userId = :userId")
  int deleteAllByUserId(@Param("userId") Integer userId);

  @Query(
      value =
          "SELECT DISTINCT t FROM socialapp.t_vault_notes v, jsonb_array_elements_text(v.tags) t"
              + " WHERE v.user_id = :userId",
      nativeQuery = true)
  List<String> findDistinctTagsByUserId(Integer userId);

  @Query("SELECT v FROM VaultNoteEntity v WHERE v.userId = :userId AND v.tags IS NOT NULL")
  List<VaultNoteEntity> findByUserIdWithTags(Integer userId);
}
