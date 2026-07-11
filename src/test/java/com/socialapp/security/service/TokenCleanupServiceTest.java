package com.socialapp.security.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.security.repository.EmailVerificationTokenRepository;
import com.socialapp.security.repository.MagicLinkTokenRepository;
import com.socialapp.security.repository.PasswordResetTokenRepository;
import com.socialapp.security.repository.RefreshTokenRepository;

/**
 * Component (unit) tests for {@link TokenCleanupService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3 BDD
 * Given/When/Then) — see {@code PostServiceTest} for the full rationale.
 */
@ExtendWith(MockitoExtension.class)
class TokenCleanupServiceTest {

  @Mock private EmailVerificationTokenRepository emailVerificationTokenRepository;
  @Mock private PasswordResetTokenRepository passwordResetTokenRepository;
  @Mock private MagicLinkTokenRepository magicLinkTokenRepository;
  @Mock private RefreshTokenRepository refreshTokenRepository;

  @InjectMocks private TokenCleanupService tokenCleanupService;

  @Test
  @DisplayName("should purge all four token tables and log a summary when something was deleted")
  void shouldPurgeAndLogSummary_whenAnyTokensWerePurged() {
    // Given
    when(emailVerificationTokenRepository.deleteByExpiresAtBefore(any())).thenReturn(2L);
    when(passwordResetTokenRepository.deleteByExpiresAtBefore(any())).thenReturn(0L);
    when(magicLinkTokenRepository.deleteByExpiresAtBefore(any())).thenReturn(0L);
    when(refreshTokenRepository.deleteByExpiresAtBefore(any())).thenReturn(0L);

    // When
    tokenCleanupService.purgeExpiredTokens();

    // Then
    verify(emailVerificationTokenRepository).deleteByExpiresAtBefore(any());
    verify(passwordResetTokenRepository).deleteByExpiresAtBefore(any());
    verify(magicLinkTokenRepository).deleteByExpiresAtBefore(any());
    verify(refreshTokenRepository).deleteByExpiresAtBefore(any());
  }

  @Test
  @DisplayName("should complete without logging a summary when nothing was purged")
  void shouldCompleteQuietly_whenNothingWasPurged() {
    // Given
    when(emailVerificationTokenRepository.deleteByExpiresAtBefore(any())).thenReturn(0L);
    when(passwordResetTokenRepository.deleteByExpiresAtBefore(any())).thenReturn(0L);
    when(magicLinkTokenRepository.deleteByExpiresAtBefore(any())).thenReturn(0L);
    when(refreshTokenRepository.deleteByExpiresAtBefore(any())).thenReturn(0L);

    // When / Then
    assertThatCode(() -> tokenCleanupService.purgeExpiredTokens()).doesNotThrowAnyException();
  }
}
