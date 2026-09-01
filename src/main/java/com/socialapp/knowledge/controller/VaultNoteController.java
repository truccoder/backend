package com.socialapp.knowledge.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.common.utils.Constants;
import com.socialapp.knowledge.dto.UpdateVaultContextSettingsDto;
import com.socialapp.knowledge.dto.VaultContextSettingsDto;
import com.socialapp.knowledge.dto.VaultNoteDetailDto;
import com.socialapp.knowledge.dto.VaultNotePageResponseDto;
import com.socialapp.knowledge.service.VaultNoteService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/**
 * What the owner can do, from a browser, with the notes their vault has synced up.
 *
 * <p><b>SESSION-AUTHENTICATED, AND THAT IS THE WHOLE REASON IT IS A SEPARATE CONTROLLER FROM {@link
 * KnowledgeSyncController}.</b> That one is on {@code permitAll} in {@code SecurityConfig} and
 * reads the {@code Authorization} header itself, because the Obsidian plugin presents a personal
 * access token rather than a session. This one must be the opposite: it is reached from a page the
 * user is already signed in to, and putting it under {@code /knowledge/sync/**} would place
 * read-and-delete of somebody's personal notes behind a `permitAll` matcher. The path is
 * {@code /v1/api/knowledge/vault/**} so no {@code sync/**} rule can ever widen to cover it.
 */
@RestController
@RequestMapping("/v1/api/knowledge/vault")
@RequiredArgsConstructor
public class VaultNoteController {

  private final VaultNoteService vaultNoteService;

  /**
   * One page of the caller's notes, newest first, without their bodies.
   *
   * <p>{@code limit} is capped at 50 for the same reason every other listing here is: a vault is
   * unbounded, and an uncapped page is a request the caller gets to size.
   */
  @GetMapping("/notes")
  public VaultNotePageResponseDto listNotes(
      @RequestParam(required = false) Integer cursor,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE_SIZE)
          @Positive
          @Max(Constants.MAX_PAGINATION_PAGE_SIZE)
          int limit) {
    return vaultNoteService.listNotes(SecurityUtils.getCurrentUserId(), cursor, limit);
  }

  @GetMapping("/notes/{noteId}")
  public VaultNoteDetailDto getNote(@PathVariable Integer noteId) {
    return vaultNoteService.getNote(SecurityUtils.getCurrentUserId(), noteId);
  }

  @DeleteMapping("/notes/{noteId}")
  public void deleteNote(@PathVariable Integer noteId) {
    vaultNoteService.deleteNote(SecurityUtils.getCurrentUserId(), noteId);
  }

  /**
   * Remove the server's copy of every note this user has synced.
   *
   * <p>Returns the number removed rather than {@code void}: "we deleted 431 notes" is the only
   * confirmation available for an action whose effect is that a list becomes empty.
   */
  @DeleteMapping("/notes")
  public int deleteAllNotes() {
    return vaultNoteService.deleteAllNotes(SecurityUtils.getCurrentUserId());
  }

  /**
   * Every distinct tag across the caller's synced notes, so the settings screen can offer real
   * choices rather than a free-text box that fails silently when it is misspelled.
   */
  @GetMapping("/tags")
  public List<String> listTags() {
    return vaultNoteService.listTags(SecurityUtils.getCurrentUserId());
  }

  /**
   * Which notes may be used as AI context.
   *
   * <p>A GET on a user who has never configured anything returns two empty lists rather than 404 —
   * "no filter" is a real, and the default, answer, so the screen has an ordinary state to render
   * instead of an error to explain.
   */
  @GetMapping("/settings")
  public VaultContextSettingsDto getSettings() {
    return vaultNoteService.getSettings(SecurityUtils.getCurrentUserId());
  }

  /** Replace the filter. A full replace, like the professional profile's PUT. */
  @PutMapping("/settings")
  public VaultContextSettingsDto updateSettings(
      @RequestBody @Valid UpdateVaultContextSettingsDto request) {
    return vaultNoteService.updateSettings(SecurityUtils.getCurrentUserId(), request);
  }
}
