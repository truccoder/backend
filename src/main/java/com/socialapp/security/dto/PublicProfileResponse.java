package com.socialapp.security.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.socialapp.knowledge.entity.enums.PrimaryRole;
import com.socialapp.knowledge.entity.enums.SeniorityLevel;

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
 * @param coverImageUrl banner across the top of the profile page, or null for a profile with no
 *     cover. Null is the ordinary case and stays supported forever — the clients draw a plain
 *     token-coloured band instead, which is what the design system allows in place of the
 *     per-user gradient this would otherwise have invited.
 * @param currentLevelMin score at which the current level starts — the floor of a progress bar
 * @param nextLevelMin score at which the next level starts, null at the top level, where there is
 *     nothing left to progress towards
 * @param jobTitle free-text title the user typed ("Senior Backend Engineer"), or null if they have
 *     never filled in a professional profile. One of four fields lifted out of {@code
 *     t_user_professional_profiles} to build the role line the design system's identity block
 *     expects between the reputation and the handle.
 *     <p><b>Four fields, not the whole record.</b> {@code ProfessionalProfileResponseDto} also
 *     carries {@code workHistory}, {@code interestedDomains}, {@code knownTechStack} and {@code
 *     explanationStyle}, and none of them belong here. The first three are somebody's actual
 *     employment history, declared so that the Gemini explainer could tailor its answers — not so
 *     that they could be published on a page any stranger can open. {@code explanationStyle} is a
 *     personal preference about how one likes to be taught, which says something about the reader
 *     and nothing about their standing. Copying the whole record over would have turned an
 *     owner-facing form into a public résumé nobody consented to.
 *     <p>That is also why the owner-facing endpoint stayed shut rather than gaining a {@code
 *     userId} variant: opening it would have published all nine fields, and the four wanted here
 *     are cheaper to carry on a payload that is already being built.
 * @param primaryRole the specialism enum ({@code BACKEND}, {@code FRONTEND}, …), null when unset
 * @param seniorityLevel the seniority enum ({@code JUNIOR}, {@code SENIOR}, …), null when unset
 * @param yearsOfExperience null when unset — and null rather than {@code 0}, because "has not said"
 *     and "has none" are different claims and the client renders them differently
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
    String coverImageUrl,
    Integer eliteScore,
    OffsetDateTime createdAt,
    Integer level,
    String levelName,
    Integer currentLevelMin,
    Integer nextLevelMin,
    String jobTitle,
    PrimaryRole primaryRole,
    SeniorityLevel seniorityLevel,
    Integer yearsOfExperience,
    List<String> verifiedSkills) {}
