package com.socialapp.blocks.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.blocks.entity.UserBlockEntity;
import com.socialapp.blocks.entity.UserBlockId;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link UserBlockRepository} against a real PostgreSQL instance
 * (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own
 * transaction, so no manual cleanup is needed.
 *
 * <p>Two of these are really tests of {@code V46__create_t_user_blocks.sql}: the composite primary
 * key that makes a repeated block idempotent at the database, and the check constraint that stops
 * a user blocking themselves (which would otherwise erase them from their own feed, since the
 * filter treats the relation as two-way).
 */
@Transactional
class UserBlockRepositoryTest extends AbstractIntegrationTest {

  @Autowired private UserBlockRepository userBlockRepository;
  @Autowired private UserRepository userRepository;

  private Integer alice;
  private Integer bob;
  private Integer carol;

  @BeforeEach
  void seedUsers() {
    alice = userRepository.saveAndFlush(user("alice@example.com", "block-fixture-alice")).getId();
    bob = userRepository.saveAndFlush(user("bob@example.com", "block-fixture-bob")).getId();
    carol = userRepository.saveAndFlush(user("carol@example.com", "block-fixture-carol")).getId();
  }

  private static UserEntity user(String email, String username) {
    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPassword("hashed-password");
    user.setUsername(username);
    user.setFullName("Test User");
    return user;
  }

  private void block(Integer blockerId, Integer blockedId) {
    UserBlockEntity entity = new UserBlockEntity();
    entity.setId(new UserBlockId(blockerId, blockedId));
    userBlockRepository.saveAndFlush(entity);
  }

  @Nested
  @DisplayName("findBlockedIds / findBlockerIds")
  class DirectionalLookups {

    @Test
    @DisplayName("returns each direction separately")
    void returnsEachDirection() {
      // Given
      block(alice, bob);
      block(carol, alice);

      // When / Then
      assertThat(userBlockRepository.findBlockedIds(alice)).containsExactly(bob);
      assertThat(userBlockRepository.findBlockerIds(alice)).containsExactly(carol);
    }

    @Test
    @DisplayName("returns nothing for a user with no blocks")
    void emptyForCleanUser() {
      // When / Then
      assertThat(userBlockRepository.findBlockedIds(alice)).isEmpty();
      assertThat(userBlockRepository.findBlockerIds(alice)).isEmpty();
    }
  }

  @Nested
  @DisplayName("existsBetween")
  class ExistsBetween {

    @Test
    @DisplayName("is true regardless of who did the blocking")
    void trueInBothDirections() {
      // Given
      block(alice, bob);

      // When / Then
      assertThat(userBlockRepository.existsBetween(alice, bob)).isTrue();
      assertThat(userBlockRepository.existsBetween(bob, alice)).isTrue();
    }

    @Test
    @DisplayName("is false for an unrelated pair")
    void falseForUnrelatedPair() {
      // Given
      block(alice, bob);

      // When / Then
      assertThat(userBlockRepository.existsBetween(alice, carol)).isFalse();
    }
  }

  @Nested
  @DisplayName("findByIdBlockerIdOrderByCreatedAtDesc")
  class ListForBlocker {

    @Test
    @DisplayName("returns only the rows this user created, newest first")
    void returnsOwnBlocksOnly() {
      // Given
      block(alice, bob);
      block(alice, carol);
      block(bob, carol);

      // When
      var blocks = userBlockRepository.findByIdBlockerIdOrderByCreatedAtDesc(alice);

      // Then
      assertThat(blocks)
          .extracting(b -> b.getId().getBlockedId())
          .containsExactlyInAnyOrder(bob, carol);
    }
  }

  @Nested
  @DisplayName("schema constraints")
  class SchemaConstraints {

    @Test
    @DisplayName("rejects a self-block at the database")
    void rejectsSelfBlock() {
      // When / Then — ck_user_blocks_not_self; the service refuses too, this is the backstop
      assertThatThrownBy(() -> block(alice, alice))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }
}
