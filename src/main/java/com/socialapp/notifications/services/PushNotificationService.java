package com.socialapp.notifications.services;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.socialapp.notifications.config.OneSignalProperties;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class PushNotificationService {
  private final WebClient webClient;
  private final OneSignalProperties properties;

  public PushNotificationService(OneSignalProperties properties) {
    this.properties = properties;
    this.webClient =
        WebClient.builder()
            .baseUrl(properties.getBaseUrl())
            .defaultHeader("Authorization", "Basic " + properties.getRestApiKey())
            .defaultHeader("Content-Type", "application/json")
            .build();
  }

  public void sendToPlayer(String playerId, String title, String body, Map<String, String> data) {
    if (Objects.isNull(playerId) || playerId.isBlank()) {
      log.debug("No player ID, skipping push notification");
      return;
    }
    sendToPlayers(List.of(playerId), title, body, data);
  }

  public void sendToPlayers(
      List<String> playerIds, String title, String body, Map<String, String> data) {
    if (playerIds.isEmpty()) return;

    try {
      Map<String, Object> payload =
          Map.of(
              "app_id", properties.getAppId(),
              "include_player_ids", playerIds,
              "headings", Map.of("en", title),
              "contents", Map.of("en", body),
              "data", Objects.requireNonNullElse(data, Map.of()));

      webClient
          .post()
          .uri("/notifications")
          .bodyValue(payload)
          .retrieve()
          .bodyToMono(Map.class)
          .block();

      log.info("Push notification sent to {} players: {}", playerIds.size(), title);
    } catch (Exception e) {
      log.error("Failed to send push notification: {}", e.getMessage());
    }
  }

  public void sendToAll(String title, String body, Map<String, String> data) {
    try {
      Map<String, Object> payload =
          Map.of(
              "app_id", properties.getAppId(),
              "included_segments", List.of("All"),
              "headings", Map.of("en", title),
              "contents", Map.of("en", body),
              "data", Objects.requireNonNullElse(data, Map.of()));

      webClient
          .post()
          .uri("/notifications")
          .bodyValue(payload)
          .retrieve()
          .bodyToMono(Map.class)
          .block();

      log.info("Push notification sent to all: {}", title);
    } catch (Exception e) {
      log.error("Failed to send broadcast push: {}", e.getMessage());
    }
  }
}
