package com.socialapp.security.repository;

import java.time.OffsetDateTime;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.security.entity.MagicLinkToken;

public interface MagicLinkTokenRepository extends JpaRepository<MagicLinkToken, String> {

  void deleteByUserId(Integer userId);

  long deleteByExpiresAtBefore(OffsetDateTime cutoff);
}
