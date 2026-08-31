package com.socialapp.knowledge.service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.knowledge.dto.ExplanationResponseDto;
import com.socialapp.knowledge.dto.SyncResponseDto;
import com.socialapp.knowledge.dto.VaultNoteDto;
import com.socialapp.knowledge.dto.VaultPushRequestDto;
import com.socialapp.knowledge.entity.ExplanationEntity;
import com.socialapp.knowledge.entity.PersonalAccessTokenEntity;
import com.socialapp.knowledge.entity.VaultNoteEntity;
import com.socialapp.knowledge.entity.enums.VaultPermission;
import com.socialapp.knowledge.repository.ExplanationRepository;
import com.socialapp.knowledge.repository.VaultNoteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeSyncService {
  private final ExplanationRepository explanationRepository;
  private final VaultNoteRepository vaultNoteRepository;
  private final PersonalAccessTokenService tokenService;

  /**
   * Pull: Obsidian plugin fetches saved explanations from app → vault.
   */
  public SyncResponseDto pull(String rawToken, String since) {
    Integer userId = tokenService.validateToken(rawToken);

    List<ExplanationEntity> explanations;
    if (Objects.nonNull(since) && !since.isBlank()) {
      // `since` is a raw query-string value. DateTimeParseException has no handler, so anything
      // that is not an ISO-8601 offset date-time — a plugin sending "yesterday", or a date with no
      // zone — used to fall through to the catch-all as a 500. It is a malformed request.
      OffsetDateTime sinceTime;
      try {
        sinceTime = OffsetDateTime.parse(since);
      } catch (DateTimeParseException e) {
        throw new ValidationException(
            "Invalid 'since' timestamp; expected an ISO-8601 offset date-time"
                + " (e.g. 2026-08-23T10:15:30+07:00)");
      }
      explanations = explanationRepository.findByUserIdUpdatedAfter(userId, sinceTime);
    } else {
      explanations = explanationRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    List<ExplanationResponseDto> dtos =
        explanations.stream()
            .map(
                e ->
                    ExplanationResponseDto.builder()
                        .id(e.getId())
                        .postId(e.getPostId())
                        .originalContent(e.getOriginalContent())
                        .explanationContent(e.getExplanationContent())
                        .concepts(e.getConcepts())
                        .prerequisites(e.getPrerequisites())
                        .complexityScore(e.getComplexityScore())
                        .version(e.getVersion())
                        .createdAt(e.getCreatedAt())
                        .build())
            .toList();

    return SyncResponseDto.builder().explanations(dtos).syncedAt(OffsetDateTime.now()).build();
  }

  /**
   * Push: Obsidian plugin sends vault notes to app for context.
   * Requires BIDIRECTIONAL permission.
   */
  @Transactional
  public void push(String rawToken, VaultPushRequestDto request) {
    PersonalAccessTokenEntity tokenEntity = tokenService.validateTokenAndGetEntity(rawToken);

    if (!VaultPermission.BIDIRECTIONAL.equals(tokenEntity.getVaultPermission())) {
      throw new ForbiddenException(
          "This token only has WRITE_ONLY permission. Upgrade to BIDIRECTIONAL to push vault notes.");
    }

    Integer userId = tokenEntity.getUserId();

    // One select for the whole batch and one saveAll, rather than a select and a save per note:
    // the plugin pushes a whole vault, so this loop scaled its round trips with the vault size.
    Map<String, VaultNoteEntity> existingByFilename =
        vaultNoteRepository
            .findByUserIdAndFilenameIn(
                userId,
                request.getNotes().stream().map(VaultNoteDto::getFilename).distinct().toList())
            .stream()
            .collect(Collectors.toMap(VaultNoteEntity::getFilename, Function.identity()));

    List<VaultNoteEntity> toSave = new ArrayList<>();
    for (VaultNoteDto note : request.getNotes()) {
      VaultNoteEntity entity =
          existingByFilename.computeIfAbsent(
              note.getFilename(),
              filename -> VaultNoteEntity.builder().userId(userId).filename(filename).build());

      entity.setContent(note.getContent());
      entity.setTags(note.getTags());
      entity.setLinks(note.getLinks());
      toSave.add(entity);
    }
    vaultNoteRepository.saveAll(toSave);

    log.info("Vault push: {} notes synced for user {}", request.getNotes().size(), userId);
  }

  /**
   * Get vault context summary for a user (used internally by ExplanationService).
   */
  public List<VaultNoteEntity> getVaultNotes(Integer userId) {
    return vaultNoteRepository.findByUserId(userId);
  }
}
