package com.socialapp.posts.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.socialapp.common.crypto.EncryptedStringConverter;

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

  /** Encrypted at rest — {@code calendar.events} is read/write access to a user's calendar. */
  @Convert(converter = EncryptedStringConverter.class)
  @Column(columnDefinition = "TEXT")
  private String accessToken;

  /**
   * Encrypted at rest, and the most sensitive value in this table: Google only issues a refresh
   * token on first consent and it does not expire until the user revokes it, so in plaintext this
   * column is permanent calendar access for anyone who reads the database.
   */
  @Convert(converter = EncryptedStringConverter.class)
  @Column(columnDefinition = "TEXT")
  private String refreshToken;

  private OffsetDateTime expiresAt;

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;
}
