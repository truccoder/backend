package com.socialapp.security.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.security.entity.RefreshToken;

/**
 * Component integration tests for {@link RefreshTokenRepository} against a real PostgreSQL
 * instance (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own
 * transaction, so no manual cleanup is needed.
 */
@Transactional
class RefreshTokenRepositoryTest extends AbstractIntegrationTest {

  @Autowired private RefreshTokenRepository refreshTokenRepository;

  private static final OffsetDateTime CUTOFF = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  private static RefreshToken token(String value, Integer userId, OffsetDateTime expiresAt) {
    return new RefreshToken(value, userId, expiresAt);
  }

  @Nested
  @DisplayName("deleteByUserId")
  class DeleteByUserId {

    @Test
    @DisplayName("removes every token belonging to the user")
    void removesAllTokensForUser() {
      // Given
      refreshTokenRepository.saveAndFlush(token("t1", 1, CUTOFF.plusDays(1)));
      refreshTokenRepository.saveAndFlush(token("t2", 1, CUTOFF.plusDays(2)));
      refreshTokenRepository.saveAndFlush(token("t3", 2, CUTOFF.plusDays(1)));

      // When
      refreshTokenRepository.deleteByUserId(1);
      refreshTokenRepository.flush();

      // Then
      assertThat(refreshTokenRepository.findAll())
          .extracting(RefreshToken::getToken)
          .containsExactly("t3");
    }

    @Test
    @DisplayName("is a no-op when the user has no tokens")
    void noopWhenUserHasNoTokens() {
      // Given
      refreshTokenRepository.saveAndFlush(token("t1", 1, CUTOFF.plusDays(1)));

      // When
      refreshTokenRepository.deleteByUserId(999);
      refreshTokenRepository.flush();

      // Then
      assertThat(refreshTokenRepository.findAll()).hasSize(1);
    }
  }

  @Nested
  @DisplayName("deleteByExpiresAtBefore")
  class DeleteByExpiresAtBefore {

    @Test
    @DisplayName("deletes a token strictly before the cutoff (boundary: cutoff - 1s)")
    void deletesTokenJustBeforeCutoff() {
      // Given
      refreshTokenRepository.saveAndFlush(token("expired", 1, CUTOFF.minusSeconds(1)));

      // When
      long deleted = refreshTokenRepository.deleteByExpiresAtBefore(CUTOFF);
      refreshTokenRepository.flush();

      // Then
      assertThat(deleted).isEqualTo(1);
      assertThat(refreshTokenRepository.findById("expired")).isEmpty();
    }

    @Test
    @DisplayName("keeps a token exactly at the cutoff (boundary: cutoff)")
    void keepsTokenExactlyAtCutoff() {
      // Given
      refreshTokenRepository.saveAndFlush(token("at-cutoff", 1, CUTOFF));

      // When
      long deleted = refreshTokenRepository.deleteByExpiresAtBefore(CUTOFF);
      refreshTokenRepository.flush();

      // Then
      assertThat(deleted).isEqualTo(0);
      assertThat(refreshTokenRepository.findById("at-cutoff")).isPresent();
    }

    @Test
    @DisplayName("keeps a token just after the cutoff (boundary: cutoff + 1s)")
    void keepsTokenJustAfterCutoff() {
      // Given
      refreshTokenRepository.saveAndFlush(token("future", 1, CUTOFF.plusSeconds(1)));

      // When
      long deleted = refreshTokenRepository.deleteByExpiresAtBefore(CUTOFF);
      refreshTokenRepository.flush();

      // Then
      assertThat(deleted).isEqualTo(0);
      assertThat(refreshTokenRepository.findById("future")).isPresent();
    }

    @Test
    @DisplayName("returns 0 when there is nothing to delete")
    void returnsZeroWhenNothingExpired() {
      // When
      long deleted = refreshTokenRepository.deleteByExpiresAtBefore(CUTOFF);

      // Then
      assertThat(deleted).isEqualTo(0);
    }
  }
}
