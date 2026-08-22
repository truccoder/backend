package com.socialapp.newsfeed.dto;

/** Which slice of their own feed the caller is asking for. */
public enum FeedScope {
  /** Everything fanned out to this user — the default, and what {@code /feed} has always returned. */
  ALL,

  /**
   * Only the posts that touch a skill the caller has had <b>verified</b>.
   *
   * <p>Verified, not claimed: a pending or rejected claim is not a skill yet, and a tab built on
   * claims would let anyone widen their own feed by filing requests. A caller with no verified
   * skills gets an empty page rather than a silent fallback to {@link #ALL} — a filter that
   * quietly stops filtering is worse than one that visibly has nothing to show.
   */
  SKILLS
}
