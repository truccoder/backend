package com.socialapp.security.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

  private Integer userId;

  @Column(name = "expires_at")
  private OffsetDateTime expiresAt;
}
