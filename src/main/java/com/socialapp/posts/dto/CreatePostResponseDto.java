package com.socialapp.posts.dto;

import com.socialapp.moderation.enums.ModerationStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What {@code POST /v1/api/posts} and {@code POST /v1/api/posts/books} return once a post is
 * created.
 *
 * <p>Both used to return {@code void}. The client that just published had no id to navigate to, so
 * it fell back to reading "my latest post" and hoping the newest row was the one it had written —
 * FE's {@code docs/backend-plan.md} B39. Returning the id here removes that guess.
 *
 * <p>{@code moderationStatus} rides along so the composer knows, in the same round trip, whether to
 * show the post or a "pending review" state without a second call. With moderation enabled a fresh
 * post is {@code PENDING_MODERATION}; with it disabled it is {@code APPROVED}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePostResponseDto {
  private Integer postId;
  private ModerationStatus moderationStatus;
}
