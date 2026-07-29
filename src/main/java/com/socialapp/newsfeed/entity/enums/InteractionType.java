package com.socialapp.newsfeed.entity.enums;

/**
 * What a user did to somebody's post — the behavioural signal feed affinity is computed from.
 *
 * <p>Only the two kinds this backend actually records. {@code SHARE} was removed because no share
 * or repost feature exists to produce one. {@code VIEW} was removed as a deliberate decision, not
 * an oversight: the only place a view could be observed is the feed being served, which would mean
 * a row per post per page load into a table a scheduled job re-reads every five minutes, and the
 * signal would feed back on itself — the feed would boost the authors the feed had already chosen
 * to show. Recording views needs sampling, deduplication and a dwell-time signal to be worth
 * anything; adding the constant back without those would make ranking worse, not better.
 *
 * <p>Safe to narrow: this enum never reaches the OpenAPI contract (no DTO exposes it), {@code
 * t_user_interactions.type} is a plain {@code varchar} with no check constraint, and the table held
 * no rows at all when the constants were dropped — see {@link
 * com.socialapp.newsfeed.service.NewsfeedService#trackInteraction}, whose write path had never been
 * wired up.
 */
public enum InteractionType {
  LIKE,
  COMMENT
}
