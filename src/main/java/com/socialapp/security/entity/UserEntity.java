package com.socialapp.security.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_users")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "users_id_generator")
  @SequenceGenerator(name = "users_id_generator", sequenceName = "q_users_id", allocationSize = 1)
  private Integer id;

  private String email;

  private String password;

  private String username;

  private String fullName;

  private String profilePictureUrl;

  private OffsetDateTime bannedUntil;

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;

  public boolean isBanned() {
    return bannedUntil != null && OffsetDateTime.now().isBefore(bannedUntil);
  }
}
