package com.socialapp.knowledge.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.ForbiddenException;
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
      OffsetDateTime sinceTime = OffsetDateTime.parse(since);
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

    for (VaultNoteDto note : request.getNotes()) {
      VaultNoteEntity entity =
          vaultNoteRepository
              .findByUserIdAndFilename(userId, note.getFilename())
              .orElseGet(
                  () ->
                      VaultNoteEntity.builder()
                          .userId(userId)
                          .filename(note.getFilename())
                          .build());

      entity.setContent(note.getContent());
      entity.setTags(note.getTags());
      entity.setLinks(note.getLinks());
      vaultNoteRepository.save(entity);
    }

    log.info("Vault push: {} notes synced for user {}", request.getNotes().size(), userId);
  }

  /**
   * Get vault context summary for a user (used internally by ExplanationService).
   */
  public List<VaultNoteEntity> getVaultNotes(Integer userId) {
    return vaultNoteRepository.findByUserId(userId);
  }
}
