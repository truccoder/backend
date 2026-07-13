package com.socialapp.posts.dto;

import com.socialapp.posts.entity.enums.ReactionType;

/** {@code reactionType} is null when the current user has not reacted to the post. */
public record MyReactionResponseDto(ReactionType reactionType) {}
