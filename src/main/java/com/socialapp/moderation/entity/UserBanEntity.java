package com.socialapp.moderation.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_user_bans")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserBanEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Integer userId;

  /**
   * The post whose violation triggered this ban, if any.
   */
  private Integer postId;

  private OffsetDateTime bannedUntil;

  @CreationTimestamp private OffsetDateTime createdAt;
}
