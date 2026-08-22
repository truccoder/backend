package com.socialapp.security.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The public profile page, as {@code GET /v1/api/users/{username}/profile} serves it.
 *
 * <p><b>Deliberately not {@link PublicUserResponse}, and not an extension of it.</b> That record is
 * the shared "some other user" shape and is returned in seven places — a post's author, the
 * reactor list, a block entry, this endpoint. The profile page needs the reputation level beside
 * the raw score, because the design system forbids the client from deriving a level from a score
 * (the thresholds live in {@code RepLevel} and a second copy on the client drifts the moment one
 * moves). Adding those fields to the shared record instead would have put a level lookup and a
 * verified-skill query behind every entry of a reactor list, which is an unbounded page — an N+1
 * introduced for one screen and paid for by all seven.
 *
 * <p>So the split is by endpoint, not by inheritance: {@link PublicUserResponse} stays thin and
 * this one is allowed to be expensive, because exactly one profile is rendered per request. The
 * same privacy boundary applies to both — no email, no role, no verification flag; see {@link
 * PublicUserResponse} for why that boundary is a type rather than a convention.
 *
 * @param level numeric tier, 1–6, from {@code RepLevel}
 * @param levelName the label that goes with it ("Contributor", "Expert", …)
 * @param currentLevelMin score at which the current level starts — the floor of a progress bar
 * @param nextLevelMin score at which the next level starts, null at the top level, where there is
 *     nothing left to progress towards
 * @param verifiedSkills names of the roadmap nodes this user has had <b>verified</b>, in roadmap
 *     order. Pending and rejected claims are absent: a rejected claim is a record of someone being
 *     told no, and a pending one is not a fact yet. This is the summary strip — the full card,
 *     with tiers and verification dates, is {@code GET /v1/api/users/{userId}/roadmap-progress}.
 */
public record PublicProfileResponse(
    Integer id,
    String username,
    String fullName,
    String profilePictureUrl,
    Integer eliteScore,
    OffsetDateTime createdAt,
    Integer level,
    String levelName,
    Integer currentLevelMin,
    Integer nextLevelMin,
    List<String> verifiedSkills) {}
