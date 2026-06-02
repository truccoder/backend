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

  private String name;

  private OffsetDateTime lastUsedAt;

  private OffsetDateTime expiresAt;

  @Enumerated(EnumType.STRING)
  @Builder.Default
  private VaultPermission vaultPermission = VaultPermission.WRITE_ONLY;

  @CreationTimestamp private OffsetDateTime createdAt;
}
