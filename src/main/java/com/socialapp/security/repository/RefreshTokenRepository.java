package com.socialapp.security.repository;

import java.time.OffsetDateTime;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.security.entity.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

  void deleteByUserId(Integer userId);

  long deleteByExpiresAtBefore(OffsetDateTime cutoff);
}
