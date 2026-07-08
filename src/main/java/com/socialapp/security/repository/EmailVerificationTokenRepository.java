package com.socialapp.security.repository;

import java.time.OffsetDateTime;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.security.entity.EmailVerificationToken;

public interface EmailVerificationTokenRepository
    extends JpaRepository<EmailVerificationToken, String> {

  void deleteByUserId(Integer userId);

  long deleteByExpiresAtBefore(OffsetDateTime cutoff);
}
