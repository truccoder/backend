package com.socialapp.notifications.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.notifications.entity.NotificationPreferenceEntity;

public interface NotificationPreferenceRepository
    extends JpaRepository<NotificationPreferenceEntity, Integer> {

  Optional<NotificationPreferenceEntity> findByUserId(Integer userId);
}
