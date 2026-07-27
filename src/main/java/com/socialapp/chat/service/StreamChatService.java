package com.socialapp.chat.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.socialapp.chat.client.StreamChatClient;
import com.socialapp.chat.config.StreamChatProperties;
import com.socialapp.chat.dto.ChatTokenResponse;
import com.socialapp.common.exception.MissingConfigurationException;
import com.socialapp.friendships.service.FriendshipService;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class StreamChatService {

  private final StreamChatProperties properties;
  private final StreamTokenSigner tokenSigner;
  private final StreamChatClient streamChatClient;
  private final FriendshipService friendshipService;
  private final UserRepository userRepository;

  public ChatTokenResponse issueToken(UserEntity user) {
    if (!properties.isConfigured()) {
      throw new MissingConfigurationException(
          "Stream Chat is not configured (missing stream.chat.api-key/api-secret); cannot issue a"
              + " chat token");
    }

    Instant issuedAt = Instant.now();
    Instant expiresAt = issuedAt.plus(properties.getTokenTtl());
    String token = tokenSigner.userToken(user.getId(), issuedAt, expiresAt);

    syncProfilesBestEffort(user);

    return ChatTokenResponse.builder()
        .userId(String.valueOf(user.getId()))
        .apiKey(properties.getApiKey())
        .streamToken(token)
        .expiresAt(expiresAt)
        .build();
  }

  /**
   * Pushes the caller <em>and every friend of theirs</em> to Stream in one call.
   *
   * <p>Stream only knows a user once someone has upserted them, and it refuses to create a channel
   * whose members it has never seen ("The following users are involved in channel create operation,
   * but don't exist"). Syncing only the caller therefore left them unable to start a conversation
   * with anyone who had not yet opened chat themselves.
   *
   * <p>Done synchronously, on purpose: pushing it to {@code @Async} reintroduces the race this
   * fixes — the frontend receives its token and can create the channel before the upsert lands. The
   * work is bounded by one user's friend count; if it ever gets slow, remember the last sync time
   * (Redis) rather than making it asynchronous.
   *
   * <p>A failed profile sync degrades the chat UI to numeric ids; a failed token blocks chat
   * entirely. Never let the former cause the latter — Stream lazily creates the user on {@code
   * connectUser} anyway.
   */
  private void syncProfilesBestEffort(UserEntity user) {
    try {
      List<UserEntity> toSync = new ArrayList<>();
      toSync.add(user);

      List<Integer> friendIds = friendshipService.getFriendIds(user.getId());
      if (!friendIds.isEmpty()) {
        // findAllById() silently skips ids missing from Postgres, which is exactly what we want
        // for friend edges left dangling in Neo4j by a deleted account.
        userRepository
            .findAllById(friendIds)
            .forEach(
                friend -> {
                  if (!friend.getId().equals(user.getId())) {
                    toSync.add(friend);
                  }
                });
      }

      streamChatClient.upsertUsers(toSync);
      log.info("Synced {} user(s) to Stream Chat for user {}", toSync.size(), user.getId());
    } catch (Exception e) {
      log.warn(
          "Could not sync user {} and friends to Stream Chat; issuing token anyway",
          user.getId(),
          e);
    }
  }
}
