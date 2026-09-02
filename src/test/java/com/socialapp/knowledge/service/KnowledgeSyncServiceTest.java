package com.socialapp.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.knowledge.dto.SyncResponseDto;
import com.socialapp.knowledge.dto.VaultNoteDto;
import com.socialapp.knowledge.dto.VaultPushRequestDto;
import com.socialapp.knowledge.entity.ExplanationEntity;
import com.socialapp.knowledge.entity.PersonalAccessTokenEntity;
import com.socialapp.knowledge.entity.VaultNoteEntity;
import com.socialapp.knowledge.entity.enums.VaultPermission;
import com.socialapp.knowledge.repository.ExplanationRepository;
import com.socialapp.knowledge.repository.VaultNoteRepository;

/**
 * Component (unit) tests for {@link KnowledgeSyncService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3 BDD
 * Given/When/Then) — see {@code PostServiceTest} for the full rationale.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeSyncServiceTest {

  private static final Integer USER_ID = 1;
  private static final String RAW_TOKEN = "raw-token";

  @Mock private ExplanationRepository explanationRepository;
  @Mock private VaultNoteRepository vaultNoteRepository;
  @Mock private PersonalAccessTokenService tokenService;

  @InjectMocks private KnowledgeSyncService knowledgeSyncService;

  @Captor private ArgumentCaptor<List<VaultNoteEntity>> notesCaptor;

  private static PersonalAccessTokenEntity tokenEntity(VaultPermission permission) {
    return PersonalAccessTokenEntity.builder().userId(USER_ID).vaultPermission(permission).build();
  }

  // =====================================================================
  // pull
  // =====================================================================

  @Nested
  @DisplayName("pull")
  class PullTests {

    @Test
    @DisplayName("should return every explanation when since is null")
    void shouldReturnAllExplanations_whenSinceIsNull() {
      // Given
      when(tokenService.validateToken(RAW_TOKEN)).thenReturn(USER_ID);
      when(explanationRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
          .thenReturn(List.of(ExplanationEntity.builder().id(1).build()));

      // When
      SyncResponseDto result = knowledgeSyncService.pull(RAW_TOKEN, null);

      // Then
      assertThat(result.getExplanations()).hasSize(1);
      assertThat(result.getSyncedAt()).isNotNull();
    }

    @Test
    @DisplayName("should return every explanation when since is blank")
    void shouldReturnAllExplanations_whenSinceIsBlank() {
      // Given
      when(tokenService.validateToken(RAW_TOKEN)).thenReturn(USER_ID);
      when(explanationRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of());

      // When
      SyncResponseDto result = knowledgeSyncService.pull(RAW_TOKEN, "   ");

      // Then
      assertThat(result.getExplanations()).isEmpty();
    }

    @Test
    @DisplayName(
        "should return only explanations updated after the given timestamp when since is provided")
    void shouldReturnExplanationsUpdatedAfter_whenSinceProvided() {
      // Given
      when(tokenService.validateToken(RAW_TOKEN)).thenReturn(USER_ID);
      OffsetDateTime since = OffsetDateTime.now().minusDays(1);
      when(explanationRepository.findByUserIdUpdatedAfter(eq(USER_ID), any()))
          .thenReturn(List.of(ExplanationEntity.builder().id(2).build()));

      // When
      SyncResponseDto result = knowledgeSyncService.pull(RAW_TOKEN, since.toString());

      // Then
      assertThat(result.getExplanations()).hasSize(1);
      verify(explanationRepository, org.mockito.Mockito.never())
          .findByUserIdOrderByCreatedAtDesc(any());
    }
  }

  // =====================================================================
  // push
  // =====================================================================

  @Nested
  @DisplayName("push")
  class PushTests {

    private VaultPushRequestDto requestWithNote(String filename) {
      VaultPushRequestDto request = new VaultPushRequestDto();
      VaultNoteDto note =
          VaultNoteDto.builder()
              .filename(filename)
              .content("content")
              .tags(List.of("t"))
              .links(List.of("l"))
              .build();
      request.setNotes(List.of(note));
      return request;
    }

    @Test
    @DisplayName("should reject when the token only has WRITE_ONLY permission")
    void shouldThrowForbiddenException_whenPermissionIsWriteOnly() {
      // Given
      when(tokenService.validateTokenAndGetEntity(RAW_TOKEN))
          .thenReturn(tokenEntity(VaultPermission.WRITE_ONLY));

      // When / Then
      assertThatThrownBy(() -> knowledgeSyncService.push(RAW_TOKEN, requestWithNote("f.md")))
          .isInstanceOf(ForbiddenException.class);
      verify(vaultNoteRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("should create a new vault note when none exists for that filename")
    void shouldCreateNewVaultNote_whenNoneExistsForFilename() {
      // Given
      when(tokenService.validateTokenAndGetEntity(RAW_TOKEN))
          .thenReturn(tokenEntity(VaultPermission.BIDIRECTIONAL));
      // The whole batch is looked up in one query now, and written with one saveAll.
      when(vaultNoteRepository.findByUserIdAndFilenameIn(USER_ID, List.of("f.md")))
          .thenReturn(List.of());

      // When
      knowledgeSyncService.push(RAW_TOKEN, requestWithNote("f.md"));

      // Then
      verify(vaultNoteRepository).saveAll(notesCaptor.capture());
      assertThat(notesCaptor.getValue())
          .singleElement()
          .satisfies(
              saved -> {
                assertThat(saved.getUserId()).isEqualTo(USER_ID);
                assertThat(saved.getFilename()).isEqualTo("f.md");
                assertThat(saved.getContent()).isEqualTo("content");
              });
    }

    @Test
    @DisplayName("should update the existing vault note when one is present for that filename")
    void shouldUpdateExistingVaultNote_whenPresent() {
      // Given
      when(tokenService.validateTokenAndGetEntity(RAW_TOKEN))
          .thenReturn(tokenEntity(VaultPermission.BIDIRECTIONAL));
      VaultNoteEntity existing =
          VaultNoteEntity.builder().id(5).userId(USER_ID).filename("f.md").content("old").build();
      when(vaultNoteRepository.findByUserIdAndFilenameIn(USER_ID, List.of("f.md")))
          .thenReturn(List.of(existing));

      // When
      knowledgeSyncService.push(RAW_TOKEN, requestWithNote("f.md"));

      // Then — the same managed row is updated in place, not replaced
      verify(vaultNoteRepository).saveAll(notesCaptor.capture());
      assertThat(notesCaptor.getValue()).containsExactly(existing);
      assertThat(existing.getContent()).isEqualTo("content");
    }
  }

  // =====================================================================
  // getVaultNotes
  // =====================================================================

  @Nested
  @DisplayName("getVaultNotes")
  class GetVaultNotesTests {

    @Test
    @DisplayName("should return every vault note owned by the user")
    void shouldReturnNotesForUser() {
      // Given
      when(vaultNoteRepository.findByUserId(USER_ID))
          .thenReturn(List.of(VaultNoteEntity.builder().id(1).userId(USER_ID).build()));

      // When / Then
      assertThat(knowledgeSyncService.getVaultNotes(USER_ID)).hasSize(1);
    }
  }
}
