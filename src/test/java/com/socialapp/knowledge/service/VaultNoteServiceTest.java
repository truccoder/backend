package com.socialapp.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.knowledge.dto.VaultNoteDetailDto;
import com.socialapp.knowledge.dto.VaultNotePageResponseDto;
import com.socialapp.knowledge.entity.VaultNoteEntity;
import com.socialapp.knowledge.repository.VaultNoteRepository;

/**
 * Component (unit) tests for {@link VaultNoteService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 4.3.2 branch testing, Section 2.1.3 BDD Given/When/Then) — see {@code
 * PostServiceTest} for the full rationale.
 *
 * <p>The ownership cases are the reason this class exists. Note ids come from a sequence, so an id
 * belonging to one vault is a plausible id in every other one; a lookup that forgot to filter by
 * user would hand somebody else's personal notes to any signed-in caller who counted upwards.
 */
@ExtendWith(MockitoExtension.class)
class VaultNoteServiceTest {

  private static final Integer USER_ID = 1;
  private static final Integer OTHER_USER_ID = 2;
  private static final Integer NOTE_ID = 10;

  @Mock private VaultNoteRepository vaultNoteRepository;

  @InjectMocks private VaultNoteService vaultNoteService;

  private static VaultNoteEntity note(Integer id, String filename) {
    return VaultNoteEntity.builder()
        .id(id)
        .userId(USER_ID)
        .filename(filename)
        .content("body")
        .tags(List.of("t"))
        .links(List.of())
        .build();
  }

  @Nested
  @DisplayName("listNotes")
  class ListNotesTests {

    @Test
    @DisplayName("should report hasMore and trim the extra row when a page is full")
    void shouldTrimTheProbeRow() {
      // Given — the service asks for limit + 1 rows so it can answer hasMore without a count
      // query. The extra row must not reach the caller.
      List<VaultNoteEntity> rows =
          IntStream.rangeClosed(1, 11).mapToObj(i -> note(i, "n" + i + ".md")).toList();
      when(vaultNoteRepository.findPage(eq(USER_ID), isNull(), any())).thenReturn(rows);

      // When
      VaultNotePageResponseDto page = vaultNoteService.listNotes(USER_ID, null, 10);

      // Then
      assertThat(page.items()).hasSize(10);
      assertThat(page.hasMore()).isTrue();
      assertThat(page.nextCursor()).isEqualTo(10);
    }

    @Test
    @DisplayName("should report no more when the page is short")
    void shouldReportEndOfList() {
      // Given — BVA: exactly one fewer row than the probe would have returned
      when(vaultNoteRepository.findPage(eq(USER_ID), isNull(), any()))
          .thenReturn(List.of(note(1, "a.md"), note(2, "b.md")));

      // When
      VaultNotePageResponseDto page = vaultNoteService.listNotes(USER_ID, null, 10);

      // Then
      assertThat(page.items()).hasSize(2);
      assertThat(page.hasMore()).isFalse();
      assertThat(page.nextCursor()).isEqualTo(2);
    }

    @Test
    @DisplayName("should return a null cursor for an empty vault")
    void shouldHandleEmptyVault() {
      // Given
      when(vaultNoteRepository.findPage(eq(USER_ID), isNull(), any())).thenReturn(List.of());

      // When
      VaultNotePageResponseDto page = vaultNoteService.listNotes(USER_ID, null, 10);

      // Then — a cursor taken from the last row of an empty list would be an index crash
      assertThat(page.items()).isEmpty();
      assertThat(page.hasMore()).isFalse();
      assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("should never expose note bodies in the list payload")
    void shouldOmitBodies() {
      // Given — the summary record has no content component at all, which is the point: a list of
      // a 500-note vault must not ship 500 note bodies to render a column of filenames
      when(vaultNoteRepository.findPage(eq(USER_ID), isNull(), any()))
          .thenReturn(List.of(note(1, "a.md")));

      // When
      VaultNotePageResponseDto page = vaultNoteService.listNotes(USER_ID, null, 10);

      // Then
      assertThat(page.items().get(0).filename()).isEqualTo("a.md");
      assertThat(page.items().get(0).getClass().getRecordComponents())
          .extracting(java.lang.reflect.RecordComponent::getName)
          .doesNotContain("content");
    }
  }

  @Nested
  @DisplayName("getNote")
  class GetNoteTests {

    @Test
    @DisplayName("should return the note with its body")
    void shouldReturnBody() {
      // Given
      when(vaultNoteRepository.findByIdAndUserId(NOTE_ID, USER_ID))
          .thenReturn(Optional.of(note(NOTE_ID, "a.md")));

      // When
      VaultNoteDetailDto detail = vaultNoteService.getNote(USER_ID, NOTE_ID);

      // Then
      assertThat(detail.filename()).isEqualTo("a.md");
      assertThat(detail.content()).isEqualTo("body");
    }

    @Test
    @DisplayName("should 404 for a note belonging to another user_security")
    void shouldNotLeakAnotherUsersNote() {
      // Given — the repository filters by user, so another user's id resolves to nothing
      when(vaultNoteRepository.findByIdAndUserId(NOTE_ID, OTHER_USER_ID))
          .thenReturn(Optional.empty());

      // When / Then — 404 rather than 403: a 403 would confirm the id exists, which is enough to
      // map another person's vault by walking the sequence
      assertThatThrownBy(() -> vaultNoteService.getNote(OTHER_USER_ID, NOTE_ID))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("not found");
    }
  }

  @Nested
  @DisplayName("deleteNote")
  class DeleteNoteTests {

    @Test
    @DisplayName("should delete the caller's own note")
    void shouldDeleteOwnNote() {
      // Given
      VaultNoteEntity entity = note(NOTE_ID, "a.md");
      when(vaultNoteRepository.findByIdAndUserId(NOTE_ID, USER_ID)).thenReturn(Optional.of(entity));

      // When
      vaultNoteService.deleteNote(USER_ID, NOTE_ID);

      // Then
      verify(vaultNoteRepository).delete(entity);
    }

    @Test
    @DisplayName("should refuse to delete another user's note_security")
    void shouldNotDeleteAnotherUsersNote() {
      // Given
      when(vaultNoteRepository.findByIdAndUserId(NOTE_ID, OTHER_USER_ID))
          .thenReturn(Optional.empty());

      // When / Then — and nothing may be deleted on the way to the exception
      assertThatThrownBy(() -> vaultNoteService.deleteNote(OTHER_USER_ID, NOTE_ID))
          .isInstanceOf(NotFoundException.class);
      verify(vaultNoteRepository, never()).delete(any());
    }
  }

  @Nested
  @DisplayName("deleteAllNotes")
  class DeleteAllTests {

    @Test
    @DisplayName("should wipe only the caller's own notes and report the count")
    void shouldWipeOwnVault() {
      // Given
      when(vaultNoteRepository.deleteAllByUserId(USER_ID)).thenReturn(431);

      // When
      int removed = vaultNoteService.deleteAllNotes(USER_ID);

      // Then — the count is the only confirmation available for an action whose visible effect is
      // that a list becomes empty
      assertThat(removed).isEqualTo(431);
      verify(vaultNoteRepository).deleteAllByUserId(USER_ID);
    }
  }
}
