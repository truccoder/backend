package com.socialapp.knowledge.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.socialapp.knowledge.entity.enums.VaultPermission;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_personal_access_tokens")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonalAccessTokenEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "access_tokens_seq_gen")
  @SequenceGenerator(
      name = "access_tokens_seq_gen",
      sequenceName = "q_access_tokens_id",
      allocationSize = 1)
  private Integer id;

  private Integer userId;

  private String tokenHash;

  /**
   * The first characters of the raw token, e.g. {@code "sk_7tDWi2xy"} — the only remaining way to
   * tell two tokens apart once the raw value has left the create dialog, since only
   * {@link #tokenHash} is otherwise stored. {@code null} for tokens created before this column
   * existed; there is no way to recover a prefix for those from a hash.
   */
  private String tokenPrefix;

  private String name;

  private OffsetDateTime lastUsedAt;

  private OffsetDateTime expiresAt;

  @Enumerated(EnumType.STRING)
  @Builder.Default
  private VaultPermission vaultPermission = VaultPermission.WRITE_ONLY;

  @CreationTimestamp private OffsetDateTime createdAt;
}
