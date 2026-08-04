package com.socialapp.security.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;

/**
 * Component integration tests for {@link UserRepository} against a real PostgreSQL instance
 * (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1 (component integration testing). Each
 * test runs inside a transaction that is rolled back afterwards, so no manual cleanup is needed
 * and test cases never see each other's data.
 */
@Transactional
class UserRepositoryTest extends AbstractIntegrationTest {

  @Autowired private UserRepository userRepository;

  private static UserEntity user(String email, String username, String fullName) {
    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPassword("hashed-password");
    user.setUsername(username);
    user.setFullName(fullName);
    user.setEmailVerified(true);
    user.setRole(UserRole.USER);
    return user;
  }

  @Nested
  @DisplayName("existsByEmailIgnoreCase")
  class ExistsByEmailIgnoreCase {

    @Test
    @DisplayName("returns true when an existing email differs only by case")
    void returnsTrueForCaseInsensitiveMatch() {
      // Given
      userRepository.saveAndFlush(user("Alice@Example.com", "fixture-alice", "Alice Nguyen"));

      // When
      boolean exists = userRepository.existsByEmailIgnoreCase("alice@example.com");

      // Then
      assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("returns false when no user has the given email")
    void returnsFalseWhenEmailNotFound() {
      // When
      boolean exists = userRepository.existsByEmailIgnoreCase("ghost@example.com");

      // Then
      assertThat(exists).isFalse();
    }
  }

  @Nested
  @DisplayName("findByEmail")
  class FindByEmail {

    @Test
    @DisplayName("finds a user by exact email match")
    void findsUserByExactEmail() {
      // Given
      userRepository.saveAndFlush(user("bob@example.com", "fixture-bob", "Bob Tran"));

      // When
      Optional<UserEntity> result = userRepository.findByEmail("bob@example.com");

      // Then
      assertThat(result).isPresent();
      assertThat(result.get().getUsername()).isEqualTo("fixture-bob");
    }

    @Test
    @DisplayName("is case-sensitive, unlike findByEmailIgnoreCase")
    void doesNotMatchDifferentCase() {
      // Given
      userRepository.saveAndFlush(user("Carol@Example.com", "fixture-carol", "Carol Le"));

      // When
      Optional<UserEntity> result = userRepository.findByEmail("carol@example.com");

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("findByEmailIgnoreCase")
  class FindByEmailIgnoreCase {

    @Test
    @DisplayName("finds a user regardless of email case")
    void findsUserIgnoringCase() {
      // Given
      userRepository.saveAndFlush(user("Dave@Example.com", "fixture-dave", "Dave Pham"));

      // When
      Optional<UserEntity> result = userRepository.findByEmailIgnoreCase("dave@example.com");

      // Then
      assertThat(result).isPresent();
      assertThat(result.get().getEmail()).isEqualTo("Dave@Example.com");
    }

    @Test
    @DisplayName("returns empty when no user matches")
    void returnsEmptyWhenNoMatch() {
      // When
      Optional<UserEntity> result = userRepository.findByEmailIgnoreCase("nobody@example.com");

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("search")
  class Search {

    @Test
    @DisplayName("matches by full name ignoring diacritics and case")
    void matchesFullNameIgnoringAccentsAndCase() {
      // Given
      UserEntity viet =
          userRepository.saveAndFlush(user("viet@example.com", "viet", "Nguyễn Văn Việt"));
      userRepository.saveAndFlush(user("other@example.com", "other", "Someone Else"));

      // When
      Page<UserEntity> result =
          userRepository.search("nguyen van viet", List.of(), List.of(-1), PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).extracting(UserEntity::getId).containsExactly(viet.getId());
    }

    @Test
    @DisplayName("matches by username substring")
    void matchesUsernameSubstring() {
      // Given
      UserEntity target =
          userRepository.saveAndFlush(user("eve@example.com", "fixture-eve-reader", "Eve Doan"));
      userRepository.saveAndFlush(user("frank@example.com", "frank_writer", "Frank Vo"));

      // When
      Page<UserEntity> result =
          userRepository.search("reader", List.of(), List.of(-1), PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).extracting(UserEntity::getId).containsExactly(target.getId());
    }

    @Test
    @DisplayName("ranks friends before non-friends, both alphabetically")
    void ranksFriendsBeforeNonFriends() {
      // Given
      UserEntity stranger =
          userRepository.saveAndFlush(user("s1@example.com", "s_bao", "Bao Zolarion"));
      UserEntity friend =
          userRepository.saveAndFlush(user("s2@example.com", "s_zoe", "Zoe Zolarion"));

      // When
      Page<UserEntity> result =
          userRepository.search(
              "zolarion", List.of(friend.getId()), List.of(-1), PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent())
          .extracting(UserEntity::getId)
          .containsExactly(friend.getId(), stranger.getId());
    }

    @Test
    @DisplayName("returns an empty page when nothing matches")
    void returnsEmptyPageWhenNoMatch() {
      // Given
      userRepository.saveAndFlush(user("greg@example.com", "greg", "Greg Ho"));

      // When
      Page<UserEntity> result =
          userRepository.search("zzz_no_match", List.of(), List.of(-1), PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("respects the requested page size")
    void respectsPageSize() {
      // Given
      for (int i = 0; i < 3; i++) {
        userRepository.saveAndFlush(
            user("paged" + i + "@example.com", "paged" + i, "Paged User " + i));
      }
      Pageable firstPageOfTwo = PageRequest.of(0, 2);

      // When
      Page<UserEntity> result =
          userRepository.search("paged", List.of(), List.of(-1), firstPageOfTwo);

      // Then
      assertThat(result.getContent()).hasSize(2);
      assertThat(result.getTotalElements()).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("findByUsernameIgnoreCase / existsByUsernameIgnoreCase")
  class UsernameLookups {

    @Test
    @DisplayName("finds a user whatever case the handle is typed in")
    void findsRegardlessOfCase() {
      // Given
      UserEntity ada = userRepository.saveAndFlush(user("ada@example.com", "ada-lovelace", "Ada"));

      // When / Then — the profile URL is /u/{username}, and a link typed with different casing
      // has to reach the same person the unique index considers a duplicate
      assertThat(userRepository.findByUsernameIgnoreCase("ADA-Lovelace"))
          .map(UserEntity::getId)
          .contains(ada.getId());
      assertThat(userRepository.existsByUsernameIgnoreCase("Ada-LOVELACE")).isTrue();
    }

    @Test
    @DisplayName("finds nothing for an unknown handle")
    void emptyForUnknownHandle() {
      // When / Then
      assertThat(userRepository.findByUsernameIgnoreCase("nobody-here")).isEmpty();
      assertThat(userRepository.existsByUsernameIgnoreCase("nobody-here")).isFalse();
    }

    @Test
    @DisplayName("rejects a second user whose handle differs only by case")
    void rejectsCaseOnlyDuplicate() {
      // Given
      userRepository.saveAndFlush(user("first@example.com", "duplicate-handle", "First"));

      // When / Then — uq_users_username_lower from V47. Without the lower() in the index, both
      // rows would be stored and /u/duplicate-handle would be ambiguous.
      assertThatThrownBy(
              () ->
                  userRepository.saveAndFlush(
                      user("second@example.com", "Duplicate-Handle", "Second")))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }
}
