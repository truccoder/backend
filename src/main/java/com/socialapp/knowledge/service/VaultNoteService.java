package com.socialapp.knowledge.service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.knowledge.dto.UpdateVaultContextSettingsDto;
import com.socialapp.knowledge.dto.VaultContextSettingsDto;
import com.socialapp.knowledge.dto.VaultNoteDetailDto;
import com.socialapp.knowledge.dto.VaultNotePageResponseDto;
import com.socialapp.knowledge.dto.VaultNoteSummaryDto;
import com.socialapp.knowledge.entity.VaultContextSettingsEntity;
import com.socialapp.knowledge.entity.VaultNoteEntity;
import com.socialapp.knowledge.repository.VaultContextSettingsRepository;
import com.socialapp.knowledge.repository.VaultNoteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The owner's own view of the notes their vault has pushed up.
 *
 * <p><b>WHY THIS EXISTS AT ALL.</b> {@code KnowledgeSyncService.push} stores up to 500 notes per
 * request, keyed to a user, and until now the only endpoints that could reach a stored note
 * authenticated with a personal access token — i.e. only the Obsidian plugin could see them. A
 * person who synced their vault and then changed their mind had no way, anywhere in the product, to
 * find out what the server was holding or to make it stop holding it. That is not a missing
 * convenience; it is personal notes with no delete button.
 *
 * <p>So this service is deliberately session-authenticated and separate from {@code
 * KnowledgeSyncService}: the two have different callers (a browser with a JWT versus a plugin with
 * a PAT), different auth models, and different jobs — that one syncs, this one lets somebody look
 * at and remove what was synced.
 *
 * <p><b>EVERY METHOD TAKES {@code userId} AND EVERY QUERY FILTERS BY IT.</b> Note ids come from a
 * sequence, so an id from one vault is a valid-looking id in another; a lookup by id alone would
 * let any signed-in user walk the sequence and read other people's notes. Same rule and same
 * 404-not-403 choice as {@code PersonalAccessTokenService.revokeToken}: a note you may not read
 * must not be distinguishable from one that does not exist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VaultNoteService {

  private final VaultNoteRepository vaultNoteRepository;
  private final VaultContextSettingsRepository settingsRepository;

  public VaultNotePageResponseDto listNotes(Integer userId, Integer cursor, int limit) {
    // limit + 1 to learn whether anything follows without a second count query — same trick as
    // BookService.getLibraryPage.
    List<VaultNoteEntity> page =
        vaultNoteRepository.findPage(userId, cursor, PageRequest.of(0, limit + 1));

    boolean hasMore = page.size() > limit;
    List<VaultNoteEntity> visible = hasMore ? page.subList(0, limit) : page;

    List<VaultNoteSummaryDto> items = visible.stream().map(VaultNoteService::toSummary).toList();
    Integer nextCursor = visible.isEmpty() ? null : visible.get(visible.size() - 1).getId();

    return new VaultNotePageResponseDto(items, nextCursor, hasMore);
  }

  public VaultNoteDetailDto getNote(Integer userId, Integer noteId) {
    VaultNoteEntity note = requireOwnNote(userId, noteId);
    return new VaultNoteDetailDto(
        note.getId(),
        note.getFilename(),
        note.getContent(),
        note.getTags(),
        note.getLinks(),
        note.getCreatedAt(),
        note.getUpdatedAt());
  }

  /**
   * Remove one note from the server.
   *
   * <p>It does <b>not</b> touch the vault on the user's disk, and it does not stop the plugin
   * pushing the same note again on its next sync — the note is the plugin's, this row is only the
   * server's copy of it. The UI has to say so, or "delete" reads as a promise this cannot keep.
   */
  @Transactional
  public void deleteNote(Integer userId, Integer noteId) {
    vaultNoteRepository.delete(requireOwnNote(userId, noteId));
  }

  /** Wipe the server's copy of the whole vault. Same caveat as {@link #deleteNote}. */
  @Transactional
  public int deleteAllNotes(Integer userId) {
    int removed = vaultNoteRepository.deleteAllByUserId(userId);
    log.info("Vault wipe: {} notes removed for user {}", removed, userId);
    return removed;
  }

  /**
   * Every distinct tag across this user's synced notes, for the settings screen to offer.
   *
   * <p>Read from the notes rather than from the settings row: the point of the list is to let
   * somebody pick from what they actually have, and a tag they used to use but no longer do should
   * not keep appearing as a suggestion.
   */
  public List<String> listTags(Integer userId) {
    return vaultNoteRepository.findDistinctTagsByUserId(userId);
  }

  public VaultContextSettingsDto getSettings(Integer userId) {
    // Absent means "never configured", which reads as no filtering — the behaviour the product
    // had before these settings existed. It is deliberately NOT a get-or-create: writing a row
    // for every reader who opens the screen would turn a privacy choice into a default anybody
    // could later mistake for one somebody made.
    return settingsRepository
        .findById(userId)
        .map(e -> new VaultContextSettingsDto(e.getIncludeTags(), e.getExcludeTags()))
        .orElseGet(() -> new VaultContextSettingsDto(List.of(), List.of()));
  }

  /**
   * Replace this user's filter.
   *
   * <p>A full replace rather than a patch, and null on either side is read as an empty list. A
   * privacy control that could be half-updated by omission is one a stale client can silently
   * widen — the failure would be personal notes quietly returning to the prompt, with nothing on
   * screen to show it happened.
   */
  @Transactional
  public VaultContextSettingsDto updateSettings(
      Integer userId, UpdateVaultContextSettingsDto request) {
    VaultContextSettingsEntity entity =
        settingsRepository
            .findById(userId)
            .orElseGet(() -> VaultContextSettingsEntity.builder().userId(userId).build());

    entity.setIncludeTags(
        request.getIncludeTags() == null ? List.of() : normalise(request.getIncludeTags()));
    entity.setExcludeTags(
        request.getExcludeTags() == null ? List.of() : normalise(request.getExcludeTags()));

    settingsRepository.save(entity);
    return new VaultContextSettingsDto(entity.getIncludeTags(), entity.getExcludeTags());
  }

  /**
   * Trim, drop blanks, lower-case, de-duplicate.
   *
   * <p>Tags are matched against note tags by equality, and a user typing {@code "#Private "} into
   * a text field meaning {@code "private"} would otherwise get a filter that silently matches
   * nothing — the worst possible outcome for an exclusion rule, because it fails open.
   */
  private static List<String> normalise(List<String> tags) {
    return tags.stream()
        .filter(Objects::nonNull)
        .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
        .map(tag -> tag.startsWith("#") ? tag.substring(1) : tag)
        .filter(tag -> !tag.isBlank())
        .distinct()
        .toList();
  }

  private VaultNoteEntity requireOwnNote(Integer userId, Integer noteId) {
    return vaultNoteRepository
        .findByIdAndUserId(noteId, userId)
        .orElseThrow(() -> new NotFoundException("Vault note not found"));
  }

  private static VaultNoteSummaryDto toSummary(VaultNoteEntity note) {
    return new VaultNoteSummaryDto(
        note.getId(),
        note.getFilename(),
        note.getTags(),
        note.getLinks(),
        note.getCreatedAt(),
        note.getUpdatedAt());
  }
}
