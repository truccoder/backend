package com.socialapp.knowledge.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

  @Query(
      value =
          "SELECT DISTINCT t FROM socialapp.t_vault_notes v, jsonb_array_elements_text(v.tags) t"
              + " WHERE v.user_id = :userId",
      nativeQuery = true)
  List<String> findDistinctTagsByUserId(Integer userId);

  @Query("SELECT v FROM VaultNoteEntity v WHERE v.userId = :userId AND v.tags IS NOT NULL")
  List<VaultNoteEntity> findByUserIdWithTags(Integer userId);
}
