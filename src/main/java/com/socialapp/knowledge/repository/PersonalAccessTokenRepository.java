package com.socialapp.knowledge.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.knowledge.entity.PersonalAccessTokenEntity;

public interface PersonalAccessTokenRepository
    extends JpaRepository<PersonalAccessTokenEntity, Integer> {

  Optional<PersonalAccessTokenEntity> findByTokenHash(String tokenHash);

  List<PersonalAccessTokenEntity> findByUserId(Integer userId);

  boolean existsByUserIdAndVaultPermission(
      Integer userId, com.socialapp.knowledge.entity.enums.VaultPermission permission);
}
