package com.socialapp.posts.dto;

import com.socialapp.posts.entity.enums.ReactionType;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpsertPostReactionRequestDto {
  @NotNull private ReactionType reactionType;
}
