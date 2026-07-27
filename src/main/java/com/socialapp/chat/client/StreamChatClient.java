package com.socialapp.chat.client;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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

  /** Stream rejects a {@code POST /users} payload carrying more than this many users. */
  private static final int MAX_USERS_PER_UPSERT = 100;

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
   * Creates or updates Stream profiles so the chat UI renders names and avatars instead of raw
   * numeric ids — and, more importantly, so a channel can be created with these users at all:
   * Stream rejects {@code GetOrCreateChannel} for members it has never seen.
   *
   * <p>Stream's {@code POST /users} body is already a map keyed by id, so a whole batch travels in
   * a single call. Stream caps that map at {@value #MAX_USERS_PER_UPSERT} entries per request, so
   * larger collections are chunked.
   */
  public void upsertUsers(Collection<UserEntity> users) {
    if (users.isEmpty()) {
      return;
    }

    List<UserEntity> all = List.copyOf(users);
    for (int from = 0; from < all.size(); from += MAX_USERS_PER_UPSERT) {
      upsertBatch(all.subList(from, Math.min(from + MAX_USERS_PER_UPSERT, all.size())));
    }
  }

  private void upsertBatch(List<UserEntity> batch) {
    Map<String, Object> usersById = new LinkedHashMap<>();
    for (UserEntity user : batch) {
      String id = String.valueOf(user.getId());
      Map<String, Object> streamUser = new HashMap<>();
      streamUser.put("id", id);
      streamUser.put("name", displayName(user));
      if (StringUtils.hasText(user.getProfilePictureUrl())) {
        streamUser.put("image", user.getProfilePictureUrl());
      }
      usersById.put(id, streamUser);
    }

    Map<String, Object> body = Map.of("users", usersById);

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
      throw new ExternalApiException("Failed to sync user profiles to Stream Chat", e);
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
