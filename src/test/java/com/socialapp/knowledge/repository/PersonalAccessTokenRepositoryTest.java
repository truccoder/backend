package com.socialapp.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.knowledge.entity.PersonalAccessTokenEntity;
import com.socialapp.knowledge.entity.enums.VaultPermission;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link PersonalAccessTokenRepository} against a real
 * PostgreSQL instance (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls
 * back its own transaction, so no manual cleanup is needed. {@code t_personal_access_tokens} has
 * a foreign key to a real user, so each test seeds one via {@link UserRepository}.
 */
@Transactional
class PersonalAccessTokenRepositoryTest extends AbstractIntegrationTest {

  @Autowired private PersonalAccessTokenRepository personalAccessTokenRepository;
  @Autowired private UserRepository userRepository;

  private Integer userId;

  @BeforeEach
  void seedUser() {
    userId = userRepository.saveAndFlush(user("owner@example.com", "owner")).getId();
  }

  private static UserEntity user(String email, String username) {
    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPassword("hashed-password");
    user.setUsername(username);
    user.setFullName("Test User");
    return user;
  }

  private static PersonalAccessTokenEntity token(
      Integer userId, String tokenHash, VaultPermission permission) {
    return PersonalAccessTokenEntity.builder()
        .userId(userId)
        .tokenHash(tokenHash)
        .name("token-name")
        .vaultPermission(permission)
        .build();
  }

  @Nested
  @DisplayName("findByTokenHash")
  class FindByTokenHash {

    @Test
    @DisplayName("finds a token by its exact hash")
    void findsTokenByHash() {
      // Given
      personalAccessTokenRepository.saveAndFlush(
          token(userId, "hash-abc", VaultPermission.WRITE_ONLY));

      // When
      Optional<PersonalAccessTokenEntity> result =
          personalAccessTokenRepository.findByTokenHash("hash-abc");

      // Then
      assertThat(result).isPresent();
      assertThat(result.get().getUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("returns empty when no token matches the hash")
    void returnsEmptyWhenHashNotFound() {
      // When
      Optional<PersonalAccessTokenEntity> result =
          personalAccessTokenRepository.findByTokenHash("nonexistent-hash");

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("findByUserId")
  class FindByUserId {

    @Test
    @DisplayName("returns every token belonging to the user")
    void returnsAllTokensForUser() {
      // Given
      Integer otherUserId = userRepository.saveAndFlush(user("other@example.com", "other")).getId();
      personalAccessTokenRepository.saveAndFlush(
          token(userId, "hash-1", VaultPermission.WRITE_ONLY));
      personalAccessTokenRepository.saveAndFlush(
          token(userId, "hash-2", VaultPermission.BIDIRECTIONAL));
      personalAccessTokenRepository.saveAndFlush(
          token(otherUserId, "hash-3", VaultPermission.WRITE_ONLY));

      // When
      List<PersonalAccessTokenEntity> result = personalAccessTokenRepository.findByUserId(userId);

      // Then
      assertThat(result)
          .extracting(PersonalAccessTokenEntity::getTokenHash)
          .containsExactlyInAnyOrder("hash-1", "hash-2");
    }

    @Test
    @DisplayName("returns an empty list when the user has no tokens")
    void returnsEmptyListWhenNoneExist() {
      // When
      List<PersonalAccessTokenEntity> result = personalAccessTokenRepository.findByUserId(userId);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("existsByUserIdAndVaultPermission")
  class ExistsByUserIdAndVaultPermission {

    @Test
    @DisplayName("returns true when the user has a token with the given permission")
    void returnsTrueWhenMatchingTokenExists() {
      // Given
      personalAccessTokenRepository.saveAndFlush(
          token(userId, "hash-1", VaultPermission.BIDIRECTIONAL));

      // When
      boolean exists =
          personalAccessTokenRepository.existsByUserIdAndVaultPermission(
              userId, VaultPermission.BIDIRECTIONAL);

      // Then
      assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("returns false when the user's tokens have a different permission")
    void returnsFalseWhenPermissionDiffers() {
      // Given
      personalAccessTokenRepository.saveAndFlush(
          token(userId, "hash-1", VaultPermission.WRITE_ONLY));

      // When
      boolean exists =
          personalAccessTokenRepository.existsByUserIdAndVaultPermission(
              userId, VaultPermission.BIDIRECTIONAL);

      // Then
      assertThat(exists).isFalse();
    }
  }
}
