package com.socialapp.posts.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.socialapp.posts.entity.enums.ReactionType;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A reaction on a comment — the same shape as {@link PostReactionEntity}, one level down.
 *
 * <p>Shares {@link ReactionType} with posts rather than declaring a narrower set, though a comment
 * now only ever holds LIKE — see {@code CommentReactionService#upsertReaction}, which is where that
 * rule lives. The column stays wide on purpose: a second enum would have to be kept in step with
 * the first forever, and the rows written before the rule are migrated rather than made
 * unreadable.
 */
@Entity
@Table(name = "t_comment_reactions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentReactionEntity {
  @EmbeddedId private CommentReactionId id;

  @Enumerated(EnumType.STRING)
  private ReactionType reactionType;

  @CreationTimestamp private OffsetDateTime createdAt;
}
