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
 * caller's own tech stack crossed with the project's open roles, and their interested domains
 * crossed with the project's tags. A suggestion a user cannot explain to themselves is one they do
 * not trust, so the reason ships with the recommendation rather than being recomputed by the client.
 */
public record SuggestedProjectDto(
    ProjectResponseDto project,
    int matchScore,
    List<String> matchedSkills,
    List<String> matchedDomains) {}
