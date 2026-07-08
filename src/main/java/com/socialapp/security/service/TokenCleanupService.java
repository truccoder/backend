package com.socialapp.security.service;

import java.time.OffsetDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.security.repository.EmailVerificationTokenRepository;
import com.socialapp.security.repository.MagicLinkTokenRepository;
import com.socialapp.security.repository.PasswordResetTokenRepository;
import com.socialapp.security.repository.RefreshTokenRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Expired email-verification / password-reset / magic-link / refresh tokens were previously only
 * ever removed when someone happened to reuse them (see AuthService), so an expired token that
 * nobody retries just sits in its table forever. This sweeps all four tables on a fixed interval
 * so they don't grow unbounded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenCleanupService {

  private final EmailVerificationTokenRepository emailVerificationTokenRepository;
  private final PasswordResetTokenRepository passwordResetTokenRepository;
  private final MagicLinkTokenRepository magicLinkTokenRepository;
  private final RefreshTokenRepository refreshTokenRepository;

  private static final long FIXED_DELAY_MS = 60L * 60 * 1000; // hourly
  private static final long INITIAL_DELAY_MS = 5L * 60 * 1000; // 5 minutes after startup

  @Scheduled(fixedDelay = FIXED_DELAY_MS, initialDelay = INITIAL_DELAY_MS)
  @Transactional
  public void purgeExpiredTokens() {
    OffsetDateTime now = OffsetDateTime.now();

    long verificationDeleted = emailVerificationTokenRepository.deleteByExpiresAtBefore(now);
    long resetDeleted = passwordResetTokenRepository.deleteByExpiresAtBefore(now);
    long magicLinkDeleted = magicLinkTokenRepository.deleteByExpiresAtBefore(now);
    long refreshDeleted = refreshTokenRepository.deleteByExpiresAtBefore(now);

    if (verificationDeleted + resetDeleted + magicLinkDeleted + refreshDeleted > 0) {
      log.info(
          "TokenCleanupService: purged expired tokens - emailVerification={}, passwordReset={},"
              + " magicLink={}, refresh={}",
          verificationDeleted,
          resetDeleted,
          magicLinkDeleted,
          refreshDeleted);
    }
  }
}
