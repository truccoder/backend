package com.socialapp.chat.service;

import java.time.Instant;

import org.springframework.stereotype.Service;

import com.socialapp.chat.client.StreamChatClient;
import com.socialapp.chat.config.StreamChatProperties;
import com.socialapp.chat.dto.ChatTokenResponse;
import com.socialapp.common.exception.MissingConfigurationException;
import com.socialapp.security.entity.UserEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class StreamChatService {

  private final StreamChatProperties properties;
  private final StreamTokenSigner tokenSigner;
  private final StreamChatClient streamChatClient;

  public ChatTokenResponse issueToken(UserEntity user) {
    if (!properties.isConfigured()) {
      throw new MissingConfigurationException(
          "Stream Chat is not configured (missing stream.chat.api-key/api-secret); cannot issue a"
              + " chat token");
    }

    Instant issuedAt = Instant.now();
    Instant expiresAt = issuedAt.plus(properties.getTokenTtl());
    String token = tokenSigner.userToken(user.getId(), issuedAt, expiresAt);

    syncProfileBestEffort(user);

    return ChatTokenResponse.builder()
        .userId(String.valueOf(user.getId()))
        .apiKey(properties.getApiKey())
        .streamToken(token)
        .expiresAt(expiresAt)
        .build();
  }

  /**
   * A failed profile sync degrades the chat UI to numeric ids; a failed token blocks chat entirely.
   * Never let the former cause the latter — Stream lazily creates the user on {@code connectUser}
   * anyway.
   */
  private void syncProfileBestEffort(UserEntity user) {
    try {
      streamChatClient.upsertUser(user);
    } catch (Exception e) {
      log.warn("Could not sync user {} to Stream Chat; issuing token anyway", user.getId(), e);
    }
  }
}
