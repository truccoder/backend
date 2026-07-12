package com.socialapp.security.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.security.entity.EmailVerificationToken;

/**
 * Component integration tests for {@link EmailVerificationTokenRepository} against a real
 * PostgreSQL instance (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back
 * its own transaction, so no manual cleanup is needed.
 */
@Transactional
class EmailVerificationTokenRepositoryTest extends AbstractIntegrationTest {

  @Autowired private EmailVerificationTokenRepository emailVerificationTokenRepository;

  private static final OffsetDateTime CUTOFF = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  private static EmailVerificationToken token(
      String value, Integer userId, OffsetDateTime expiresAt) {
    return new EmailVerificationToken(value, userId, expiresAt);
  }

  @Nested
  @DisplayName("deleteByUserId")
  class DeleteByUserId {

    @Test
    @DisplayName("removes every token belonging to the user")
    void removesAllTokensForUser() {
      // Given
      emailVerificationTokenRepository.saveAndFlush(token("t1", 1, CUTOFF.plusDays(1)));
      emailVerificationTokenRepository.saveAndFlush(token("t2", 1, CUTOFF.plusDays(2)));
      emailVerificationTokenRepository.saveAndFlush(token("t3", 2, CUTOFF.plusDays(1)));

      // When
      emailVerificationTokenRepository.deleteByUserId(1);
      emailVerificationTokenRepository.flush();

      // Then
      assertThat(emailVerificationTokenRepository.findAll())
          .extracting(EmailVerificationToken::getToken)
          .containsExactly("t3");
    }

    @Test
    @DisplayName("is a no-op when the user has no tokens")
    void noopWhenUserHasNoTokens() {
      // Given
      emailVerificationTokenRepository.saveAndFlush(token("t1", 1, CUTOFF.plusDays(1)));

      // When
      emailVerificationTokenRepository.deleteByUserId(999);
      emailVerificationTokenRepository.flush();

      // Then
      assertThat(emailVerificationTokenRepository.findAll()).hasSize(1);
    }
  }

  @Nested
  @DisplayName("deleteByExpiresAtBefore")
  class DeleteByExpiresAtBefore {

    @Test
    @DisplayName("deletes a token strictly before the cutoff (boundary: cutoff - 1s)")
    void deletesTokenJustBeforeCutoff() {
      // Given
      emailVerificationTokenRepository.saveAndFlush(token("expired", 1, CUTOFF.minusSeconds(1)));

      // When
      long deleted = emailVerificationTokenRepository.deleteByExpiresAtBefore(CUTOFF);
      emailVerificationTokenRepository.flush();

      // Then
      assertThat(deleted).isEqualTo(1);
      assertThat(emailVerificationTokenRepository.findById("expired")).isEmpty();
    }

    @Test
    @DisplayName("keeps a token exactly at the cutoff (boundary: cutoff)")
    void keepsTokenExactlyAtCutoff() {
      // Given
      emailVerificationTokenRepository.saveAndFlush(token("at-cutoff", 1, CUTOFF));

      // When
      long deleted = emailVerificationTokenRepository.deleteByExpiresAtBefore(CUTOFF);
      emailVerificationTokenRepository.flush();

      // Then
      assertThat(deleted).isEqualTo(0);
      assertThat(emailVerificationTokenRepository.findById("at-cutoff")).isPresent();
    }

    @Test
    @DisplayName("keeps a token just after the cutoff (boundary: cutoff + 1s)")
    void keepsTokenJustAfterCutoff() {
      // Given
      emailVerificationTokenRepository.saveAndFlush(token("future", 1, CUTOFF.plusSeconds(1)));

      // When
      long deleted = emailVerificationTokenRepository.deleteByExpiresAtBefore(CUTOFF);
      emailVerificationTokenRepository.flush();

      // Then
      assertThat(deleted).isEqualTo(0);
      assertThat(emailVerificationTokenRepository.findById("future")).isPresent();
    }

    @Test
    @DisplayName("returns 0 when there is nothing to delete")
    void returnsZeroWhenNothingExpired() {
      // When
      long deleted = emailVerificationTokenRepository.deleteByExpiresAtBefore(CUTOFF);

      // Then
      assertThat(deleted).isEqualTo(0);
    }
  }
}
