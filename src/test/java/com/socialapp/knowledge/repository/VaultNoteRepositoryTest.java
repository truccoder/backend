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
import com.socialapp.knowledge.entity.VaultNoteEntity;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link VaultNoteRepository} against a real PostgreSQL instance
 * (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own
 * transaction, so no manual cleanup is needed. {@code t_vault_notes} has a foreign key to a real
 * user, so each test seeds one via {@link UserRepository}.
 */
@Transactional
class VaultNoteRepositoryTest extends AbstractIntegrationTest {

  @Autowired private VaultNoteRepository vaultNoteRepository;
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

  private static VaultNoteEntity note(
      Integer userId, String filename, List<String> tags, List<String> links) {
    return VaultNoteEntity.builder()
        .userId(userId)
        .filename(filename)
        .content("content")
        .tags(tags)
        .links(links)
        .build();
  }

  @Nested
  @DisplayName("findByUserIdAndFilename")
  class FindByUserIdAndFilename {

    @Test
    @DisplayName("finds a note by user and filename")
    void findsNoteByUserAndFilename() {
      // Given
      vaultNoteRepository.saveAndFlush(note(userId, "note.md", null, null));

      // When
      Optional<VaultNoteEntity> result =
          vaultNoteRepository.findByUserIdAndFilename(userId, "note.md");

      // Then
      assertThat(result).isPresent();
    }

    @Test
    @DisplayName("does not match the same filename owned by a different user")
    void ignoresOtherUsersNote() {
      // Given
      Integer otherUserId = userRepository.saveAndFlush(user("other@example.com", "other")).getId();
      vaultNoteRepository.saveAndFlush(note(otherUserId, "note.md", null, null));

      // When
      Optional<VaultNoteEntity> result =
          vaultNoteRepository.findByUserIdAndFilename(userId, "note.md");

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns empty when the filename does not exist")
    void returnsEmptyWhenFilenameNotFound() {
      // When
      Optional<VaultNoteEntity> result =
          vaultNoteRepository.findByUserIdAndFilename(userId, "missing.md");

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("findByUserId")
  class FindByUserId {

    @Test
    @DisplayName("returns every note belonging to the user")
    void returnsAllNotesForUser() {
      // Given
      Integer otherUserId = userRepository.saveAndFlush(user("other@example.com", "other")).getId();
      vaultNoteRepository.saveAndFlush(note(userId, "a.md", null, null));
      vaultNoteRepository.saveAndFlush(note(userId, "b.md", null, null));
      vaultNoteRepository.saveAndFlush(note(otherUserId, "c.md", null, null));

      // When
      List<VaultNoteEntity> result = vaultNoteRepository.findByUserId(userId);

      // Then
      assertThat(result)
          .extracting(VaultNoteEntity::getFilename)
          .containsExactlyInAnyOrder("a.md", "b.md");
    }

    @Test
    @DisplayName("returns an empty list when the user has no notes")
    void returnsEmptyListWhenNoneExist() {
      // When
      List<VaultNoteEntity> result = vaultNoteRepository.findByUserId(userId);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("findDistinctTagsByUserId")
  class FindDistinctTagsByUserId {

    @Test
    @DisplayName("flattens and deduplicates tags across the user's notes")
    void flattensAndDeduplicatesTags() {
      // Given
      vaultNoteRepository.saveAndFlush(note(userId, "a.md", List.of("java", "spring"), null));
      vaultNoteRepository.saveAndFlush(note(userId, "b.md", List.of("spring", "postgres"), null));

      // When
      List<String> result = vaultNoteRepository.findDistinctTagsByUserId(userId);

      // Then
      assertThat(result).containsExactlyInAnyOrder("java", "spring", "postgres");
    }

    @Test
    @DisplayName("excludes tags belonging to a different user")
    void excludesOtherUsersTags() {
      // Given
      Integer otherUserId = userRepository.saveAndFlush(user("other@example.com", "other")).getId();
      vaultNoteRepository.saveAndFlush(note(otherUserId, "a.md", List.of("secret-tag"), null));

      // When
      List<String> result = vaultNoteRepository.findDistinctTagsByUserId(userId);

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns an empty list when the user's notes have no tags")
    void returnsEmptyListWhenNoTags() {
      // Given
      vaultNoteRepository.saveAndFlush(note(userId, "a.md", null, null));

      // When
      List<String> result = vaultNoteRepository.findDistinctTagsByUserId(userId);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("findByUserIdWithTags")
  class FindByUserIdWithTags {

    @Test
    @DisplayName("returns only notes that have tags")
    void returnsOnlyNotesWithTags() {
      // Given
      VaultNoteEntity tagged =
          vaultNoteRepository.saveAndFlush(note(userId, "tagged.md", List.of("java"), null));
      vaultNoteRepository.saveAndFlush(note(userId, "untagged.md", null, null));

      // When
      List<VaultNoteEntity> result = vaultNoteRepository.findByUserIdWithTags(userId);

      // Then
      assertThat(result).extracting(VaultNoteEntity::getId).containsExactly(tagged.getId());
    }

    @Test
    @DisplayName("returns an empty list when none of the user's notes have tags")
    void returnsEmptyListWhenNoNotesHaveTags() {
      // Given
      vaultNoteRepository.saveAndFlush(note(userId, "untagged.md", null, null));

      // When
      List<VaultNoteEntity> result = vaultNoteRepository.findByUserIdWithTags(userId);

      // Then
      assertThat(result).isEmpty();
    }
  }
}
