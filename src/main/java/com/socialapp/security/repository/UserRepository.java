package com.socialapp.security.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.security.entity.UserEntity;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
  boolean existsByEmail(String email);
}
