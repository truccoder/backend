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
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.socialapp.chat.config.StreamChatProperties;
import com.socialapp.chat.service.StreamTokenSigner;
import com.socialapp.common.exception.ExternalApiException;
import com.socialapp.security.entity.UserEntity;

import lombok.extern.slf4j.Slf4j;

/**
 * Thin wrapper over the Stream Chat REST API.
 *
 * <p>The handful of calls needed — pushing display profiles, mirroring a block, creating a group
 * channel — go through the WebClient already configured for every other outbound integration in the
 * app rather than pulling in the {@code io.getstream:stream-chat-java} SDK, which authenticates
 * through a static singleton configured from environment variables and would sit awkwardly beside
 * Spring's config binding.
 */
@Component
@Slf4j
public class StreamChatClient {

  /**
   * The Stream channel type group conversations are created under. Public because the response the
   * frontend receives has to name it — hard-coding "messaging" on both sides is how the two drift.
   */
  public static final String GROUP_CHANNEL_TYPE = "messaging";

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

  /**
   * Tells Stream that {@code blockerId} has blocked {@code blockedId}.
   *
   * <p>This is the half of blocking that {@link #upsertUsers} cannot do. Leaving a blocked pair out
   * of the profile sync only stops <em>this</em> server from introducing them; the frontend holds a
   * Stream user token and talks to Stream directly, so once Stream knows both users — which it does
   * as soon as each has chatted with anyone at all — a channel between them is created without this
   * backend being asked. Stream has to be told about the block itself, or the block simply does not
   * apply to chat.
   *
   * <p>One-directional by Stream's model: the block belongs to {@code user_id}. The product's block
   * is mutual, so {@code StreamChatService} sends both directions.
   */
  public void blockUser(Integer blockerId, Integer blockedId) {
    postUserBlock("/users/block", blockerId, blockedId);
  }

  /** Lifts a {@link #blockUser} on Stream's side. */
  public void unblockUser(Integer blockerId, Integer blockedId) {
    postUserBlock("/users/unblock", blockerId, blockedId);
  }

  private void postUserBlock(String path, Integer blockerId, Integer blockedId) {
    Map<String, Object> body =
        Map.of(
            "user_id", String.valueOf(blockerId),
            "blocked_user_id", String.valueOf(blockedId));

    try {
      streamChatWebClient
          .post()
          .uri(
              uriBuilder ->
                  uriBuilder.path(path).queryParam("api_key", properties.getApiKey()).build())
          .header("Authorization", tokenSigner.serverToken())
          .header("Stream-Auth-Type", "jwt")
          .bodyValue(body)
          .retrieve()
          .bodyToMono(String.class)
          .block();
    } catch (Exception e) {
      throw new ExternalApiException("Failed to apply block on Stream Chat (" + path + ")", e);
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

  /**
   * Creates the group channel server-side, so this backend — not the browser — decides who is in it
   * and who owns it.
   *
   * <p>A direct message needs no call here: the frontend holds a Stream user token and creates that
   * channel itself once {@link #upsertUsers} has introduced the pair. A group is different in two
   * ways that make the client the wrong place for it. The membership has to be checked against this
   * app's blocks before it exists, and {@code created_by_id} — the field that makes someone the
   * channel owner, the only member who may rename or delete it — can only be set on a server-side
   * call. Left to the browser, both become whatever the client claims.
   *
   * <p>Stream's create-a-channel verb is {@code POST /channels/{type}/{id}/query}, the same
   * endpoint as reading one; the channel comes into being if it did not exist. {@code state:false}
   * asks it not to send back the message history and read state, which the frontend is about to
   * fetch for itself through {@code channel.watch()}.
   *
   * <p><b>No member roles are set.</b> Stream's {@code channel_role} values come from the app's
   * permission configuration, which this code cannot see; naming a role that has not been defined
   * fails the whole create. The creator still becomes the owner through {@code created_by_id},
   * which is the only privilege the product needs today.
   *
   * <p><b>Not verifiable on a dev machine</b> — same as {@link #blockUser}. Without {@code
   * stream.chat.api-key/api-secret} the service never reaches this method, so local tests exercise
   * the guard and not the request. The payload has been checked against Stream's published API
   * shape, not against a live application.
   *
   * @param channelId the id to create the channel under; must already be URL-safe
   */
  public void createGroupChannel(
      String channelId,
      String name,
      String imageUrl,
      Integer createdById,
      Collection<Integer> memberIds) {
    List<Map<String, Object>> members =
        memberIds.stream()
            .map(id -> Map.<String, Object>of("user_id", String.valueOf(id)))
            .toList();

    Map<String, Object> data = new HashMap<>();
    data.put("created_by_id", String.valueOf(createdById));
    data.put("name", name);
    data.put("members", members);
    if (StringUtils.hasText(imageUrl)) {
      data.put("image", imageUrl);
    }

    Map<String, Object> body = Map.of("data", data, "state", false);

    try {
      streamChatWebClient
          .post()
          .uri(
              uriBuilder ->
                  uriBuilder
                      .pathSegment("channels", GROUP_CHANNEL_TYPE, channelId, "query")
                      .queryParam("api_key", properties.getApiKey())
                      .build())
          .header("Authorization", tokenSigner.serverToken())
          .header("Stream-Auth-Type", "jwt")
          .bodyValue(body)
          .retrieve()
          .bodyToMono(String.class)
          .block();
    } catch (WebClientResponseException e) {
      // Stream says why it refused in the response body, and an operator needs that — a rejected
      // member id or a channel-type setting reads nothing like a network failure. It is logged
      // rather than thrown because the message travels to the caller inside the 503, and Stream's
      // errors quote back the ids and app settings involved.
      log.error(
          "Stream Chat refused to create group channel {} ({}): {}",
          channelId,
          e.getStatusCode(),
          e.getResponseBodyAsString(),
          e);
      throw new ExternalApiException("Failed to create the group channel on Stream Chat", e);
    } catch (Exception e) {
      throw new ExternalApiException("Failed to create the group channel on Stream Chat", e);
    }
  }
}
