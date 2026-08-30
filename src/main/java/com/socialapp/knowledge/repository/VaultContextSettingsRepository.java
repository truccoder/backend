package com.socialapp.knowledge.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.knowledge.entity.VaultContextSettingsEntity;

public interface VaultContextSettingsRepository
    extends JpaRepository<VaultContextSettingsEntity, Integer> {}
