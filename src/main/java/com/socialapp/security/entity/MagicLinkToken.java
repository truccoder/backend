package com.socialapp.security.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_magic_link_tokens")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MagicLinkToken {

  @Id private String token;

  private Integer userId;
  private OffsetDateTime expiresAt;
}
