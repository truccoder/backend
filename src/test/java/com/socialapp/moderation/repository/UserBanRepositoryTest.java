package com.socialapp.moderation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.moderation.entity.UserBanEntity;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link UserBanRepository} against a real PostgreSQL instance
 * (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own
 * transaction, so no manual cleanup is needed. {@code t_user_bans.user_id} is a required foreign
 * key to a real user, so each test seeds one first.
 */
@Transactional
class UserBanRepositoryTest extends AbstractIntegrationTest {

  @Autowired private UserBanRepository userBanRepository;
  @Autowired private UserRepository userRepository;

  private Integer userId;

  @BeforeEach
  void seedUser() {
    userId = userRepository.saveAndFlush(user("banned@example.com", "banned")).getId();
  }

  private static UserEntity user(String email, String username) {
    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPassword("hashed-password");
    user.setUsername(username);
    user.setFullName("Test User");
    return user;
  }

  private static UserBanEntity ban(Integer userId) {
    return UserBanEntity.builder()
        .userId(userId)
        .bannedUntil(OffsetDateTime.now().plusDays(7))
        .build();
  }

  @Nested
  @DisplayName("findByUserIdOrderByCreatedAtDesc")
  class FindByUserIdOrderByCreatedAtDesc {

    @Test
    @DisplayName("orders the user's bans newest first")
    void ordersNewestFirst() {
      // Given
      UserBanEntity first = userBanRepository.saveAndFlush(ban(userId));
      UserBanEntity second = userBanRepository.saveAndFlush(ban(userId));

      // When
      List<UserBanEntity> result = userBanRepository.findByUserIdOrderByCreatedAtDesc(userId);

      // Then
      assertThat(result)
          .extracting(UserBanEntity::getId)
          .containsExactly(second.getId(), first.getId());
    }

    @Test
    @DisplayName("returns an empty list when the user has never been banned")
    void returnsEmptyListWhenNeverBanned() {
      // When
      List<UserBanEntity> result = userBanRepository.findByUserIdOrderByCreatedAtDesc(userId);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("countByUserId")
  class CountByUserId {

    @Test
    @DisplayName("counts the number of bans for the user")
    void countsBans() {
      // Given
      userBanRepository.saveAndFlush(ban(userId));
      userBanRepository.saveAndFlush(ban(userId));

      // When
      long result = userBanRepository.countByUserId(userId);

      // Then
      assertThat(result).isEqualTo(2);
    }

    @Test
    @DisplayName("returns zero when the user has never been banned")
    void returnsZeroWhenNeverBanned() {
      // When
      long result = userBanRepository.countByUserId(userId);

      // Then
      assertThat(result).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("findBannedUserIds")
  class FindBannedUserIds {

    @Test
    @DisplayName("returns distinct banned user ids ordered by most recent ban")
    void returnsDistinctIdsOrderedByMostRecentBan() {
      // Given
      Integer otherUserId = userRepository.saveAndFlush(user("other@example.com", "other")).getId();
      userBanRepository.saveAndFlush(ban(userId));
      userBanRepository.saveAndFlush(ban(userId));
      userBanRepository.saveAndFlush(ban(otherUserId));

      // When
      Page<Integer> result = userBanRepository.findBannedUserIds(PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).containsExactlyInAnyOrder(userId, otherUserId);
      assertThat(result.getContent()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("returns an empty page when no user is banned")
    void returnsEmptyPageWhenNoBans() {
      // When
      Page<Integer> result = userBanRepository.findBannedUserIds(PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).isEmpty();
    }
  }
}
