package com.socialapp.knowledge.dto;

import java.util.List;

/**
 * A cursor page of vault notes.
 *
 * <p>Same shape and same cursor rule as {@code BookPageResponseDto}: {@code nextCursor} is the id
 * of the last note on the page and is {@code null} when nothing follows. Descending id rather than
 * descending {@code updatedAt} because a vault push writes hundreds of rows inside one
 * transaction — every one of them lands on the same timestamp, so a timestamp cursor would either
 * repeat or skip rows at every page boundary. Ids come from a sequence, so descending id is
 * descending insertion order with a unique key.
 */
public record VaultNotePageResponseDto(
    List<VaultNoteSummaryDto> items, Integer nextCursor, boolean hasMore) {}
