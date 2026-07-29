package com.socialapp.security.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;

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

  // Backstop only — no endpoint should be serializing a UserEntity in the first place. Kept so
  // that if one ever does again (as GET /roadmaps/skills/pending once did, via a lazy
  // UserRoadmapProgressEntity.user), the bcrypt hash cannot ride along in the response body.
  @JsonIgnore private String password;

  private String username;

  private String fullName;

  private String profilePictureUrl;

  private OffsetDateTime bannedUntil;

  private boolean emailVerified;

  @Enumerated(EnumType.STRING)
  private UserRole role = UserRole.USER;

  @Enumerated(EnumType.STRING)
  private AuthProvider authProvider = AuthProvider.LOCAL;

  private String providerId;

  /** Denormalized running total of {@code t_reputation_events.points}; never summed on read. */
  @Column(name = "elite_score", nullable = false)
  private Integer eliteScore = 0;

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;

  public boolean isBanned() {
    return bannedUntil != null && OffsetDateTime.now().isBefore(bannedUntil);
  }
}
