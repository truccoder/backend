package com.socialapp.posts.entity;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_quiz_answers")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuizAnswerEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @Column(name = "post_id")
  private Integer postId;

  @Column(name = "user_id")
  private Integer userId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "answers", columnDefinition = "jsonb")
  private List<Integer> answers;

  private Integer score;

  @CreationTimestamp private OffsetDateTime createdAt;
}
