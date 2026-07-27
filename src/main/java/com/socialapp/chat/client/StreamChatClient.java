package com.socialapp.chat.client;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import com.socialapp.chat.config.StreamChatProperties;
import com.socialapp.chat.service.StreamTokenSigner;
import com.socialapp.common.exception.ExternalApiException;
import com.socialapp.security.entity.UserEntity;

import lombok.extern.slf4j.Slf4j;

/**
 * Thin wrapper over the Stream Chat REST API.
 *
 * <p>Only one call is needed today — pushing the user's display profile — so this uses the WebClient
 * already configured for every other outbound integration in the app rather than pulling in the
 * {@code io.getstream:stream-chat-java} SDK, which authenticates through a static singleton
 * configured from environment variables and would sit awkwardly beside Spring's config binding.
 */
@Component
@Slf4j
public class StreamChatClient {

  private final WebClient streamChatWebClient;
  private final StreamChatProperties properties;
  private final StreamTokenSigner tokenSigner;

  public StreamChatClient(
      @Qualifier("streamChatWebClient") WebClient streamChatWebClient,
      StreamChatProperties properties,
      StreamTokenSigner tokenSigner) {
    this.streamChatWebClient = streamChatWebClient;
    this.properties = properties;
    this.tokenSigner = tokenSigner;
  }

  /**
   * Creates or updates the user's Stream profile so the chat UI renders names and avatars instead
   * of raw numeric ids.
   */
  public void upsertUser(UserEntity user) {
    Map<String, Object> streamUser = new HashMap<>();
    streamUser.put("id", String.valueOf(user.getId()));
    streamUser.put("name", displayName(user));
    if (StringUtils.hasText(user.getProfilePictureUrl())) {
      streamUser.put("image", user.getProfilePictureUrl());
    }

    Map<String, Object> body = Map.of("users", Map.of(String.valueOf(user.getId()), streamUser));

    try {
      streamChatWebClient
          .post()
          .uri(
              uriBuilder ->
                  uriBuilder.path("/users").queryParam("api_key", properties.getApiKey()).build())
          .header("Authorization", tokenSigner.serverToken())
          .header("Stream-Auth-Type", "jwt")
          .bodyValue(body)
          .retrieve()
          .bodyToMono(String.class)
          .block();
    } catch (Exception e) {
      throw new ExternalApiException("Failed to sync user profile to Stream Chat", e);
    }
  }

  private String displayName(UserEntity user) {
    if (StringUtils.hasText(user.getFullName())) {
      return user.getFullName();
    }
    if (StringUtils.hasText(user.getUsername())) {
      return user.getUsername();
    }
    return "User " + user.getId();
  }
}
