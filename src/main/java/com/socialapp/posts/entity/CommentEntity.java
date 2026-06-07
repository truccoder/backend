package com.socialapp.posts.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_comments")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "comments_seq_gen")
  @SequenceGenerator(name = "comments_seq_gen", sequenceName = "q_comments_id", allocationSize = 1)
  private Integer id;

  @Column(name = "post_id", nullable = false)
  private Integer postId;

  @Column(name = "author_id", nullable = false)
  private Integer authorId;

  @Column(nullable = false)
  private String content;

  @Column(name = "parent_id")
  private Integer parentId;

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;
}
