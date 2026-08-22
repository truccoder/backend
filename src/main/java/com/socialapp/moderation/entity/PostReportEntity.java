package com.socialapp.moderation.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.socialapp.moderation.enums.ReportReason;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One reader's report of one post.
 *
 * <p>The row is the record that the report happened, not a decision about the post — {@code
 * ModerationLogEntity} is where decisions live. Kept after the post is reviewed either way: a
 * report that turned out to be wrong is as much a part of a reporter's history as one that was
 * right, and repeat frivolous reporting is only visible if the rows survive the ruling.
 *
 * <p>{@code (postId, reporterId)} is unique in the schema — see {@code
 * V62__create_post_reports.sql} for why that constraint is the load-bearing part of this table.
 */
@Entity
@Table(name = "t_post_reports")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostReportEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "post_reports_seq_gen")
  @SequenceGenerator(
      name = "post_reports_seq_gen",
      sequenceName = "q_post_reports_id",
      allocationSize = 1)
  private Integer id;

  private Integer postId;

  private Integer reporterId;

  @Enumerated(EnumType.STRING)
  private ReportReason reason;

  @Column(columnDefinition = "TEXT")
  private String details;

  @CreationTimestamp private OffsetDateTime createdAt;
}
