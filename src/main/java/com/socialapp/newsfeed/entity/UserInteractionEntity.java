package com.socialapp.newsfeed.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.socialapp.newsfeed.entity.enums.InteractionType;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_user_interactions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserInteractionEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_interactions_id_gen")
  @SequenceGenerator(
      name = "user_interactions_id_gen",
      sequenceName = "q_user_interactions_id",
      allocationSize = 1)
  private Integer id;

  private Integer userId;
  private Integer postId;
  private Integer authorId;

  @Enumerated(EnumType.STRING)
  private InteractionType type;

  @CreationTimestamp private OffsetDateTime createdAt;
}
