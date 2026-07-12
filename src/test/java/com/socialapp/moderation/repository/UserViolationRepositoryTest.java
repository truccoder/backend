package com.socialapp.moderation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.moderation.entity.UserViolationEntity;
import com.socialapp.moderation.enums.ViolationSeverity;
import com.socialapp.moderation.enums.ViolationType;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link UserViolationRepository} against a real PostgreSQL
 * instance (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own
 * transaction, so no manual cleanup is needed. {@code t_user_violations.user_id} is a required
 * foreign key to a real user, so each test seeds one first.
 */
@Transactional
class UserViolationRepositoryTest extends AbstractIntegrationTest {

  @Autowired private UserViolationRepository userViolationRepository;
  @Autowired private UserRepository userRepository;

  private Integer userId;

  @BeforeEach
  void seedUser() {
    userId = userRepository.saveAndFlush(user("violator@example.com", "violator")).getId();
  }

  private static UserEntity user(String email, String username) {
    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPassword("hashed-password");
    user.setUsername(username);
    user.setFullName("Test User");
    return user;
  }

  private static UserViolationEntity violation(Integer userId) {
    return UserViolationEntity.builder()
        .userId(userId)
        .violationType(ViolationType.SPAM)
        .severity(ViolationSeverity.LOW)
        .build();
  }

  @Nested
  @DisplayName("findByUserIdOrderByCreatedAtDesc")
  class FindByUserIdOrderByCreatedAtDesc {

    @Test
    @DisplayName("orders the user's violations newest first")
    void ordersNewestFirst() {
      // Given
      UserViolationEntity first = userViolationRepository.saveAndFlush(violation(userId));
      UserViolationEntity second = userViolationRepository.saveAndFlush(violation(userId));

      // When
      List<UserViolationEntity> result =
          userViolationRepository.findByUserIdOrderByCreatedAtDesc(userId);

      // Then
      assertThat(result)
          .extracting(UserViolationEntity::getId)
          .containsExactly(second.getId(), first.getId());
    }

    @Test
    @DisplayName("returns an empty list when the user has no violations")
    void returnsEmptyListWhenNoViolations() {
      // When
      List<UserViolationEntity> result =
          userViolationRepository.findByUserIdOrderByCreatedAtDesc(userId);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("countByUserId")
  class CountByUserId {

    @Test
    @DisplayName("counts the number of violations for the user")
    void countsViolations() {
      // Given
      userViolationRepository.saveAndFlush(violation(userId));
      userViolationRepository.saveAndFlush(violation(userId));

      // When
      long result = userViolationRepository.countByUserId(userId);

      // Then
      assertThat(result).isEqualTo(2);
    }

    @Test
    @DisplayName("returns zero when the user has no violations")
    void returnsZeroWhenNoViolations() {
      // When
      long result = userViolationRepository.countByUserId(userId);

      // Then
      assertThat(result).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("countRecentViolations")
  class CountRecentViolations {

    @Test
    @DisplayName("includes a violation created strictly after the cutoff (boundary: cutoff - 1s)")
    void includesViolationJustAfterCutoff() {
      // Given
      UserViolationEntity saved = userViolationRepository.saveAndFlush(violation(userId));
      OffsetDateTime cutoff = saved.getCreatedAt().minusSeconds(1);

      // When
      long result = userViolationRepository.countRecentViolations(userId, cutoff);

      // Then
      assertThat(result).isEqualTo(1);
    }

    @Test
    @DisplayName("excludes a violation created exactly at the cutoff (boundary: cutoff)")
    void excludesViolationExactlyAtCutoff() {
      // Given
      UserViolationEntity saved = userViolationRepository.saveAndFlush(violation(userId));
      OffsetDateTime cutoff = saved.getCreatedAt();

      // When
      long result = userViolationRepository.countRecentViolations(userId, cutoff);

      // Then
      assertThat(result).isEqualTo(0);
    }

    @Test
    @DisplayName("excludes a violation created before the cutoff (boundary: cutoff + 1s)")
    void excludesViolationBeforeCutoff() {
      // Given
      UserViolationEntity saved = userViolationRepository.saveAndFlush(violation(userId));
      OffsetDateTime cutoff = saved.getCreatedAt().plusSeconds(1);

      // When
      long result = userViolationRepository.countRecentViolations(userId, cutoff);

      // Then
      assertThat(result).isEqualTo(0);
    }

    @Test
    @DisplayName("excludes violations belonging to a different user")
    void excludesOtherUsersViolations() {
      // Given
      Integer otherUserId = userRepository.saveAndFlush(user("other@example.com", "other")).getId();
      userViolationRepository.saveAndFlush(violation(otherUserId));

      // When
      long result =
          userViolationRepository.countRecentViolations(userId, OffsetDateTime.now().minusDays(1));

      // Then
      assertThat(result).isEqualTo(0);
    }
  }
}
