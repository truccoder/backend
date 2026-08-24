package com.socialapp.posts.dto;

import java.time.OffsetDateTime;
import java.util.Map;

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

  /**
   * The breakdown behind {@link #likeCount}, e.g. {@code {"LIKE": 3, "CLAP": 2}}.
   *
   * <p>Comments were the worse half of the asymmetry this closes. A post could always be asked
   * {@code GET /posts/{id}/reactions/summary}; a comment had no read endpoint at all, so once the
   * reaction row lost its text labels the "5" beside a single glyph became unanswerable — and
   * after tapping, the new total could only be seen by reloading the whole thread.
   *
   * <p>Costs one extra group-by per thread, batched over every comment on the page exactly as
   * {@link #likeCount} and {@link #myReaction} already are. Types nobody chose are absent rather
   * than zero.
   */
  private Map<ReactionType, Long> reactionSummary;

  /** What the caller chose on this comment, or {@code null} if they have not reacted. */
  private ReactionType myReaction;
}
