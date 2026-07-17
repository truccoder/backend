package com.socialapp.knowledge.dto;

import java.time.OffsetDateTime;

import com.socialapp.knowledge.entity.enums.VaultPermission;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonalAccessTokenResponseDto {
  private Integer id;
  private String name;
  private OffsetDateTime expiresAt;
  private OffsetDateTime lastUsedAt;
  private VaultPermission vaultPermission;
  private OffsetDateTime createdAt;
}
