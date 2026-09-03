package com.socialapp.matchmaking.dto;

import java.util.List;

/**
 * One project suggested to a user, with the reason attached.
 *
 * <p>Wraps {@link ProjectResponseDto} rather than adding nullable score fields to it. Three
 * endpoints already return {@code ProjectResponseDto} as "a project", and a {@code matchScore} that
 * is meaningful on one of them and always null on the others makes the contract worse everywhere to
 * serve one caller. Same shape and same reasoning as {@code FriendSuggestionDto(profile,
 * mutualFriends)}.
 *
 * <p>{@code matchedSkills} and {@code matchedDomains} are what produced {@code matchScore}: the
 * caller's own tech stack crossed with the roles they qualify for, and their interested domains
 * crossed with the project's tags. A suggestion a user cannot explain to themselves is one they do
 * not trust, so the reason ships with the recommendation rather than being recomputed by the client.
 *
 * <p>{@code qualifiedPositionIds} names <em>which</em> roles they qualify for, and it is never
 * empty: a project with no role for this person is not suggested at all (see {@code
 * MatchmakingService.score}). It exists because "this project fits you" is not actionable — a user
 * still has to find the role to apply to, and the matcher already knows which one it meant.
 */
public record SuggestedProjectDto(
    ProjectResponseDto project,
    int matchScore,
    List<String> matchedSkills,
    List<String> matchedDomains,
    List<Integer> qualifiedPositionIds) {}
