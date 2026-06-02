package com.socialapp.posts.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_google_calendar_tokens")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoogleCalendarTokenEntity {
  @Id private Integer userId;

  @Column(columnDefinition = "TEXT")
  private String accessToken;

  @Column(columnDefinition = "TEXT")
  private String refreshToken;

  private OffsetDateTime expiresAt;

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;
}
