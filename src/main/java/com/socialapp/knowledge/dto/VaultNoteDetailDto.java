package com.socialapp.knowledge.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One vault note including its body, for the reader who asked to see that note.
 *
 * <p>Separate from {@link VaultNoteSummaryDto} so the list endpoint can stay cheap: the body is
 * fetched one note at a time, by a person who clicked on it, rather than for every row of a vault
 * that may hold hundreds.
 */
public record VaultNoteDetailDto(
    Integer id,
    String filename,
    String content,
    List<String> tags,
    List<String> links,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
