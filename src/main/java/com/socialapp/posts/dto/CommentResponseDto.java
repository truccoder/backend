package com.socialapp.posts.dto;

import java.time.OffsetDateTime;

import com.socialapp.posts.entity.enums.ReactionType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentResponseDto {
  private Integer id;
  private Integer postId;
  private Integer authorId;

  /**
   * Deep-link key for the commenter's profile, for the same reason the feed payload carries one:
   * {@code /v1/api/users/{username}/profile} is keyed by username and nothing maps an id to it.
   */
  private String authorUsername;

  private String authorFullName;
  private String authorProfilePictureUrl;
  private String content;
  private Integer parentId;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  /**
   * How many people reacted to this comment, all types together.
   *
   * <p>The field the "top two comments" preview needs: without it there was nothing to rank by, so
   * the preview fell back to the two OLDEST comments and said so on screen.
   */
  private int likeCount;

  /** What the caller chose on this comment, or {@code null} if they have not reacted. */
  private ReactionType myReaction;
}
