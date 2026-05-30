package com.socialapp.posts.dto;

import com.socialapp.posts.entity.enums.ReactionType;

import lombok.Data;

@Data
public class UpsertPostReactionRequestDto {
  private ReactionType reactionType;
}
