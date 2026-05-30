package com.socialapp.security.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_refresh_tokens")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {

  @Id private String token;

  @Column(name = "user_id")
  private Integer userId;

  @Column(name = "expires_at")
  private OffsetDateTime expiresAt;
}
