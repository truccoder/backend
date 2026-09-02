package com.socialapp.knowledge.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One synced vault note, as the owner's browser lists it.
 *
 * <p><b>NO {@code content} FIELD, DELIBERATELY.</b> A vault runs to hundreds of notes and the
 * bodies are the bulk of every one of them, so a list endpoint that returned them would ship a
 * whole personal knowledge base over the wire to render a column of filenames. {@link
 * VaultNoteDetailDto} is the one that carries a body, one note at a time, when somebody asks to
 * read it.
 *
 * <p>{@code tags} and {@code links} stay: they are short, and they are exactly what {@code
 * ExplanationService.loadVaultContext} sends to the model — so a reader looking at this list is
 * looking at the same facts the AI was given about them.
 */
public record VaultNoteSummaryDto(
    Integer id,
    String filename,
    List<String> tags,
    List<String> links,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
