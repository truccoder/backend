package com.socialapp.posts.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_post_tags")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostTagEntity {
  @EmbeddedId private PostTagId id;
  private Integer taggedUserId;
}
