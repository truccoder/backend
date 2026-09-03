package com.socialapp.friendships.dto;

import java.util.List;

import com.socialapp.knowledge.entity.enums.PrimaryRole;

/**
 * One friend suggestion with the reason attached, so it does not reach the client as a bare name
 * and a mutual-friend count nobody can act on. Same shape and same reasoning as {@code
 * SuggestedProjectDto(project, matchScore, matchedSkills, matchedDomains, qualifiedPositionIds)}
 * — the reason ships with the recommendation rather than being recomputed by the client.
 *
 * @param sharedRole the caller's own {@code primaryRole} when the candidate's matches it exactly,
 *     {@code null} otherwise — including when either side has no professional profile at all.
 * @param matchedSkills the caller's own {@code knownTechStack} entries the candidate's list also
 *     contains, compared case-insensitively; empty when either side has no profile or nothing
 *     overlaps. See {@code ProfileMatchScorer.matchedSkills}.
 * @param sharedHashtags hashtags carried by a PUBLIC, APPROVED post from both people, most-used
 *     first, capped at a handful; empty when neither has publicly posted with a tag the other
 *     also used.
 */
public record FriendSuggestionDto(
    UserProfileDto profile,
    long mutualFriends,
    PrimaryRole sharedRole,
    List<String> matchedSkills,
    List<String> sharedHashtags) {}
