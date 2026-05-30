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

  @Enumerated(EnumType.STRING)
  private ViolationType violationType;

  @Enumerated(EnumType.STRING)
  private ViolationSeverity severity;

  private String description;

  @CreationTimestamp private OffsetDateTime createdAt;
}
