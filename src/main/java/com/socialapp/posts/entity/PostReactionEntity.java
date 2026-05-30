package com.socialapp.posts.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.socialapp.posts.entity.enums.ReactionType;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_post_reactions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostReactionEntity {
  @EmbeddedId private PostReactionId id;

  @Enumerated(EnumType.STRING)
  @Column(name = "reaction_type", nullable = false, length = 20)
  private ReactionType reactionType;

  @CreationTimestamp private OffsetDateTime createdAt;
}
