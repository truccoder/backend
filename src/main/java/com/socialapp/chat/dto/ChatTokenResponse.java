package com.socialapp.chat.dto;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatTokenResponse {
  private String userId;

  /**
   * Stream's public application key. Returned here so the frontend has a single source of truth for
   * it — the value the token was signed against — instead of its own environment variable that can
   * silently point at a different Stream app.
   */
  private String apiKey;

  private String streamToken;

  /** When {@link #streamToken} stops being accepted; the client should re-fetch before this. */
  private Instant expiresAt;
}
