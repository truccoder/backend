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
    @DisplayName("resolves several handles at once, dropping the ones nobody holds")
    void resolvesManyHandlesInOneQuery() {
      // Given - the batch form MentionScanner feeds. A comment can name up to ten people, so a
      // lookup per handle would be ten round trips on the write path of every comment with an @.
      UserEntity ada = userRepository.saveAndFlush(user("ada@example.com", "ada", "Ada"));
      UserEntity bob = userRepository.saveAndFlush(user("bob@example.com", "bob", "Bob"));

      // When
      List<UserEntity> found =
          userRepository.findAllByUsernameLowerIn(List.of("ada", "bob", "nobody-here"));

      // Then - the unknown handle is simply absent, which is the answer the caller wants: a
      // comment naming somebody who does not exist is a comment, not an error
      assertThat(found)
          .extracting(UserEntity::getId)
          .containsExactlyInAnyOrder(ada.getId(), bob.getId());
    }

    @Test
    @DisplayName("matches on the lower-cased handle, the way the unique index stores it")
    void matchesTheLowerCaseIndex() {
      // Given - a handle saved with capitals. The caller lower-cases what it scanned, so the
      // comparison has to be against lower(username) or this misses a real user.
      UserEntity ada = userRepository.saveAndFlush(user("ada@example.com", "Ada-Lovelace", "Ada"));

      // When / Then
      assertThat(userRepository.findAllByUsernameLowerIn(List.of("ada-lovelace")))
          .extracting(UserEntity::getId)
          .containsExactly(ada.getId());
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

  @Nested
  @DisplayName("findMentionableFriends")
  class FindMentionableFriends {

    @Test
    @DisplayName("returns only the caller's friends, ordered by full name")
    void returnsOnlyFriendsAlphabetically() {
      // Given: the @-dropdown before anything is typed answers with friends and nobody else
      UserEntity zoe = userRepository.saveAndFlush(user("fmf-z@example.com", "fmf-zoe", "Zoe Fmf"));
      UserEntity abe = userRepository.saveAndFlush(user("fmf-a@example.com", "fmf-abe", "Abe Fmf"));
      userRepository.saveAndFlush(user("fmf-s@example.com", "fmf-cat", "Cat Fmf"));

      // When
      List<UserEntity> result =
          userRepository.findMentionableFriends(
              List.of(zoe.getId(), abe.getId()), List.of(-1), PageRequest.of(0, 10));

      // Then
      assertThat(result).extracting(UserEntity::getId).containsExactly(abe.getId(), zoe.getId());
    }

    @Test
    @DisplayName("drops a friend who is in the exclusion list")
    void dropsExcludedFriend() {
      // Given: blocking ends the friendship, but the reader-side filter is enforced anyway
      UserEntity kept =
          userRepository.saveAndFlush(user("fmx-k@example.com", "fmx-kept", "Kept Fmx"));
      UserEntity blocked =
          userRepository.saveAndFlush(user("fmx-b@example.com", "fmx-blocked", "Blocked Fmx"));

      // When
      List<UserEntity> result =
          userRepository.findMentionableFriends(
              List.of(kept.getId(), blocked.getId()),
              List.of(blocked.getId()),
              PageRequest.of(0, 10));

      // Then
      assertThat(result).extracting(UserEntity::getId).containsExactly(kept.getId());
    }

    @Test
    @DisplayName("returns nothing for the sentinel a caller with no friends is given")
    void returnsNothingForSentinel() {
      // Given
      userRepository.saveAndFlush(user("fms-a@example.com", "fms-alone", "Alone Fms"));

      // When: MentionSuggestService substitutes -1 for an empty friend list
      List<UserEntity> result =
          userRepository.findMentionableFriends(List.of(-1), List.of(-1), PageRequest.of(0, 10));

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("suggestMentions")
  class SuggestMentions {

    @Test
    @DisplayName("ranks a friend above a non-friend even when the non-friend matches better")
    void ranksFriendsFirst() {
      // Given: the friend only contains the query, the stranger starts with it and sorts first
      UserEntity friend =
          userRepository.saveAndFlush(user("sm1-f@example.com", "sub-qmentiona", "Zzz Qmentiona"));
      UserEntity stranger =
          userRepository.saveAndFlush(user("sm1-s@example.com", "qmentiona-pre", "Aaa Qmentiona"));

      // When
      List<UserEntity> result =
          userRepository.suggestMentions(
              "qmentiona", List.of(friend.getId()), List.of(-1), PageRequest.of(0, 10));

      // Then: friendship outranks both the prefix key and the name key
      assertThat(result)
          .extracting(UserEntity::getId)
          .containsExactly(friend.getId(), stranger.getId());
    }

    @Test
    @DisplayName("ranks a prefix match above a substring match among equals")
    void ranksPrefixBeforeSubstring() {
      // Given: typing "qmentionb" means a name that STARTS with it, not one containing it
      UserEntity prefix =
          userRepository.saveAndFlush(user("sm2-p@example.com", "qmentionb-pre", "Zed Qm"));
      UserEntity substring =
          userRepository.saveAndFlush(user("sm2-s@example.com", "sub-qmentionb", "Abe Qm"));

      // When: neither is a friend, so the name key would put "Abe" first without the prefix key
      List<UserEntity> result =
          userRepository.suggestMentions(
              "qmentionb", List.of(-1), List.of(-1), PageRequest.of(0, 10));

      // Then
      assertThat(result)
          .extracting(UserEntity::getId)
          .containsExactly(prefix.getId(), substring.getId());
    }

    @Test
    @DisplayName("matches a full name ignoring diacritics, like the search box does")
    void matchesAccentInsensitively() {
      // Given
      UserEntity target =
          userRepository.saveAndFlush(user("sm3@example.com", "qmentionc-u", "Trần Qmentionc"));

      // When
      List<UserEntity> result =
          userRepository.suggestMentions(
              "tran qmentionc", List.of(-1), List.of(-1), PageRequest.of(0, 10));

      // Then
      assertThat(result).extracting(UserEntity::getId).containsExactly(target.getId());
    }

    @Test
    @DisplayName("drops anyone in the exclusion list, which always holds the caller")
    void dropsExcluded() {
      // Given
      UserEntity caller =
          userRepository.saveAndFlush(user("sm4-c@example.com", "qmentiond-me", "Me Qmentiond"));
      UserEntity other =
          userRepository.saveAndFlush(user("sm4-o@example.com", "qmentiond-you", "You Qmentiond"));

      // When
      List<UserEntity> result =
          userRepository.suggestMentions(
              "qmentiond", List.of(-1), List.of(caller.getId()), PageRequest.of(0, 10));

      // Then
      assertThat(result).extracting(UserEntity::getId).containsExactly(other.getId());
    }

    @Test
    @DisplayName("respects the requested page size")
    void respectsPageSize() {
      // Given
      for (int i = 0; i < 3; i++) {
        userRepository.saveAndFlush(
            user("sm5-" + i + "@example.com", "qmentione-" + i, "Qmentione User " + i));
      }

      // When
      List<UserEntity> result =
          userRepository.suggestMentions(
              "qmentione", List.of(-1), List.of(-1), PageRequest.of(0, 2));

      // Then
      assertThat(result).hasSize(2);
    }
  }
}
