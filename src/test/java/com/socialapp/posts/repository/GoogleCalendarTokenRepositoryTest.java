package com.socialapp.posts.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.posts.entity.GoogleCalendarTokenEntity;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link GoogleCalendarTokenRepository} against a real
 * PostgreSQL instance (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls
 * back its own transaction, so no manual cleanup is needed. {@code t_google_calendar_tokens} is
 * keyed by a real user id, so each test seeds one first.
 */
@Transactional
class GoogleCalendarTokenRepositoryTest extends AbstractIntegrationTest {

  @Autowired private GoogleCalendarTokenRepository googleCalendarTokenRepository;
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

  private static GoogleCalendarTokenEntity token(Integer userId) {
    return GoogleCalendarTokenEntity.builder()
        .userId(userId)
        .accessToken("access-token")
        .refreshToken("refresh-token")
        .expiresAt(OffsetDateTime.now().plusHours(1))
        .build();
  }

  @Nested
  @DisplayName("findByUserId")
  class FindByUserId {

    @Test
    @DisplayName("finds a previously stored token for the user")
    void findsExistingToken() {
      // Given
      googleCalendarTokenRepository.saveAndFlush(token(userId));

      // When
      Optional<GoogleCalendarTokenEntity> result =
          googleCalendarTokenRepository.findByUserId(userId);

      // Then
      assertThat(result).isPresent();
      assertThat(result.get().getAccessToken()).isEqualTo("access-token");
    }

    @Test
    @DisplayName("returns empty when the user has no stored token")
    void returnsEmptyWhenNoTokenStored() {
      // When
      Optional<GoogleCalendarTokenEntity> result =
          googleCalendarTokenRepository.findByUserId(userId);

      // Then
      assertThat(result).isEmpty();
    }
  }
}
