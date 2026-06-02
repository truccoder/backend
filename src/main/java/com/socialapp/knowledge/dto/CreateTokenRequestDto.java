package com.socialapp.knowledge.dto;

import com.socialapp.knowledge.entity.enums.VaultPermission;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateTokenRequestDto {
  @NotBlank private String name;

  private Integer expiresInDays;

  private VaultPermission vaultPermission;
}
