package com.socialapp.knowledge.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.socialapp.knowledge.entity.VaultNoteEntity;

public interface VaultNoteRepository extends JpaRepository<VaultNoteEntity, Integer> {

  Optional<VaultNoteEntity> findByUserIdAndFilename(Integer userId, String filename);

  List<VaultNoteEntity> findByUserId(Integer userId);

  @Query("SELECT DISTINCT t FROM VaultNoteEntity v, UNNEST(v.tags) t" + " WHERE v.userId = :userId")
  List<String> findDistinctTagsByUserId(Integer userId);

  @Query("SELECT v FROM VaultNoteEntity v WHERE v.userId = :userId AND v.tags IS NOT NULL")
  List<VaultNoteEntity> findByUserIdWithTags(Integer userId);
}
