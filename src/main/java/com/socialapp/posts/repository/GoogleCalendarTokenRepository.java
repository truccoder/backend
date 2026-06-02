package com.socialapp.posts.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.posts.entity.GoogleCalendarTokenEntity;

public interface GoogleCalendarTokenRepository
    extends JpaRepository<GoogleCalendarTokenEntity, Integer> {

  Optional<GoogleCalendarTokenEntity> findByUserId(Integer userId);
}
