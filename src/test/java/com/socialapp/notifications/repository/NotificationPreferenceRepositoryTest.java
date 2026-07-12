package com.socialapp.notifications.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.notifications.entity.NotificationPreferenceEntity;
import com.socialapp.notifications.entity.enums.EmailFrequency;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link NotificationPreferenceRepository} against a real
 * PostgreSQL instance (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls
 * back its own transaction, so no manual cleanup is needed. {@code t_notification_preferences}
 * is keyed by a real user id, so each test seeds one first.
 */
@Transactional
class NotificationPreferenceRepositoryTest extends AbstractIntegrationTest {

  @Autowired private NotificationPreferenceRepository notificationPreferenceRepository;
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

  private static NotificationPreferenceEntity preference(Integer userId) {
    return NotificationPreferenceEntity.builder()
        .userId(userId)
        .pushEnabled(false)
        .emailFrequency(EmailFrequency.WEEKLY_DIGEST)
        .build();
  }

  @Nested
  @DisplayName("findByUserId")
  class FindByUserId {

    @Test
    @DisplayName("finds the stored preferences for the user")
    void findsExistingPreferences() {
      // Given
      notificationPreferenceRepository.saveAndFlush(preference(userId));

      // When
      Optional<NotificationPreferenceEntity> result =
          notificationPreferenceRepository.findByUserId(userId);

      // Then
      assertThat(result).isPresent();
      assertThat(result.get().getEmailFrequency()).isEqualTo(EmailFrequency.WEEKLY_DIGEST);
    }

    @Test
    @DisplayName("returns empty when the user has no stored preferences")
    void returnsEmptyWhenNoPreferencesStored() {
      // When
      Optional<NotificationPreferenceEntity> result =
          notificationPreferenceRepository.findByUserId(userId);

      // Then
      assertThat(result).isEmpty();
    }
  }
}
