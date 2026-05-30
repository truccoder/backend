package com.socialapp.posts.entity;

import java.io.Serial;
import java.io.Serializable;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostReactionId implements Serializable {
  @Serial private static final long serialVersionUID = 1L;

  private Integer userId;
  private Integer postId;
}
