package com.socialapp.knowledge.dto;

import java.time.OffsetDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTokenResponseDto {
  private Integer id;
  private String token;
  private String name;
  private OffsetDateTime expiresAt;
}
