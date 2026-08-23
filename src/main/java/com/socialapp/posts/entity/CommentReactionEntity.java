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
 * <p>Shares {@link ReactionType} with posts rather than declaring a narrower set: a reader who can
 * mark a post as INSIGHT has the same thing to say about the answer underneath it, and a second
 * enum would have to be kept in step with the first forever.
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
