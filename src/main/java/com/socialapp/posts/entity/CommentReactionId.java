package com.socialapp.posts.entity;

import java.io.Serial;
import java.io.Serializable;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Composite key of {@link CommentReactionEntity} — one reaction per user per comment. */
@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentReactionId implements Serializable {
  @Serial private static final long serialVersionUID = 1L;

  private Integer userId;
  private Integer commentId;
}
