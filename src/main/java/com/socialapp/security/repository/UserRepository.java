package com.socialapp.security.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.security.entity.UserEntity;

public interface UserRepository extends JpaRepository<UserEntity, Integer> {
  boolean existsByEmailIgnoreCase(String email);

  Optional<UserEntity> findByEmail(String email);

  @Query("SELECT u FROM UserEntity u WHERE LOWER(u.email) = LOWER(:email)")
  Optional<UserEntity> findByEmailIgnoreCase(@Param("email") String email);
}
