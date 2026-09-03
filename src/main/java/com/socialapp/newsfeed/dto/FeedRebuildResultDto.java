package com.socialapp.newsfeed.dto;

/**
 * What a feed rebuild actually did.
 *
 * <p>Returned rather than a bare 204 because the operation is invisible from outside: its whole
 * effect lands in Redis, so without these numbers there is no way to tell a rebuild that fanned
 * out two hundred posts from one that silently matched none.
 *
 * @param processed posts successfully fanned out
 * @param skipped posts that threw and were left behind — see the log for which and why
 */
public record FeedRebuildResultDto(int processed, int skipped) {}
