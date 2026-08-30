package com.socialapp.newsfeed.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MarkSeenRequestDto {
  /**
   * The posts that have been on screen long enough to count as read.
   *
   * <p>Sent in batches, not one request per post. The client fires this when a feed card leaves the
   * viewport having been visible for at least a second or covering at least half its area, and gathers
   * the cards that qualify into a single call when scrolling settles — a request per card would mean
   * a request per scroll tick, which is the cost that makes view tracking not worth doing at all.
   *
   * <p>The ceiling is what stops an authenticated caller from growing their own seen set without
   * bound; a viewport's worth of cards is a handful, so two hundred is a limit on accidental bulk
   * rather than on ordinary use. It also bounds the argument list of the Lua script behind {@code
   * NewsfeedService.markSeen}.
   *
   * <p><b>Not validated against the post table.</b> The Redis key these ids land in is derived from
   * the caller's own authenticated id, so the only thing a bogus id can affect is the order of the
   * sender's own feed — and checking would cost exactly the per-scroll database query this whole
   * design exists to avoid.
   */
  @NotEmpty
  @Size(max = 200, message = "At most 200 post ids may be reported as seen in one request")
  private List<@NotNull @Positive Integer> postIds;
}
