package com.socialapp.moderation.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.socialapp.moderation.enums.ViolationSeverity;
import com.socialapp.moderation.enums.ViolationType;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_user_violations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserViolationEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Integer userId;

  private Integer postId;

  /**
   * A snapshot of what the post said, taken when the violation was recorded.
   *
   * <p>{@code postId} references {@code t_posts} with {@code ON DELETE SET NULL} (V9) — a post
   * deleted after the fact leaves this violation with no live target, and the row would go from
   * "here is what you did" to "something, we don't remember what". This column exists so the
   * panel can still say which post it was even then; it is never re-derived from {@code postId}
   * after the fact, since the point is to survive the post's deletion.
   */
  private String postExcerpt;

  @Enumerated(EnumType.STRING)
  private ViolationType violationType;

  @Enumerated(EnumType.STRING)
  private ViolationSeverity severity;

  private String description;

  @CreationTimestamp private OffsetDateTime createdAt;
}
