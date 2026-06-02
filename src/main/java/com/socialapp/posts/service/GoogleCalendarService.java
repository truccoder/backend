package com.socialapp.posts.service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.socialapp.posts.config.GoogleCalendarProperties;
import com.socialapp.posts.entity.EventDetails;
import com.socialapp.posts.entity.GoogleCalendarTokenEntity;
import com.socialapp.posts.repository.GoogleCalendarTokenRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleCalendarService {
  private final GoogleCalendarProperties properties;
  private final GoogleCalendarTokenRepository tokenRepository;
  private final WebClient.Builder webClientBuilder;

  public String getAuthorizationUrl(Integer userId) {
    return UriComponentsBuilder.fromHttpUrl(properties.getAuthUrl())
        .queryParam("client_id", properties.getClientId())
        .queryParam("redirect_uri", properties.getRedirectUri())
        .queryParam("response_type", "code")
        .queryParam("scope", properties.getScope())
        .queryParam("access_type", "offline")
        .queryParam("prompt", "consent")
        .queryParam("state", String.valueOf(userId))
        .build()
        .toUriString();
  }

  @SuppressWarnings("unchecked")
  public void handleOAuthCallback(Integer userId, String code) {
    WebClient webClient = webClientBuilder.build();

    Map<String, Object> tokenResponse =
        webClient
            .post()
            .uri(properties.getTokenUrl())
            .bodyValue(
                Map.of(
                    "code", code,
                    "client_id", properties.getClientId(),
                    "client_secret", properties.getClientSecret(),
                    "redirect_uri", properties.getRedirectUri(),
                    "grant_type", "authorization_code"))
            .retrieve()
            .bodyToMono(Map.class)
            .block();

    if (Objects.isNull(tokenResponse)) {
      throw new RuntimeException("Failed to exchange OAuth code for tokens");
    }

    String accessToken = (String) tokenResponse.get("access_token");
    String refreshToken = (String) tokenResponse.get("refresh_token");
    Number expiresIn = (Number) tokenResponse.get("expires_in");

    GoogleCalendarTokenEntity entity =
        tokenRepository
            .findByUserId(userId)
            .orElseGet(() -> GoogleCalendarTokenEntity.builder().userId(userId).build());

    entity.setAccessToken(accessToken);
    if (Objects.nonNull(refreshToken)) {
      entity.setRefreshToken(refreshToken);
    }
    entity.setExpiresAt(OffsetDateTime.now().plusSeconds(expiresIn.longValue()));
    tokenRepository.save(entity);

    log.info("Google Calendar connected for user {}", userId);
  }

  public void addEventToCalendar(Integer userId, EventDetails event) {
    GoogleCalendarTokenEntity token =
        tokenRepository
            .findByUserId(userId)
            .orElseThrow(
                () ->
                    new RuntimeException("Google Calendar not connected. Please authorize first."));

    String accessToken = getValidAccessToken(token);

    String timezone =
        Objects.nonNull(event.getTimezone()) ? event.getTimezone() : "Asia/Ho_Chi_Minh";

    Map<String, Object> calendarEvent =
        Map.of(
            "summary", event.getEventTitle(),
            "description", Objects.toString(event.getEventDescription(), ""),
            "location", Objects.toString(event.getLocation(), ""),
            "start",
                Map.of(
                    "dateTime",
                    event.getStartTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    "timeZone",
                    timezone),
            "end",
                Map.of(
                    "dateTime",
                    event.getEndTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    "timeZone",
                    timezone));

    WebClient webClient = webClientBuilder.build();

    webClient
        .post()
        .uri(properties.getCalendarApiUrl() + "/calendars/primary/events")
        .header("Authorization", "Bearer " + accessToken)
        .bodyValue(calendarEvent)
        .retrieve()
        .bodyToMono(Map.class)
        .block();

    log.info("Event '{}' added to calendar for user {}", event.getEventTitle(), userId);
  }

  public boolean isConnected(Integer userId) {
    return tokenRepository.findByUserId(userId).isPresent();
  }

  @SuppressWarnings("unchecked")
  private String getValidAccessToken(GoogleCalendarTokenEntity token) {
    if (OffsetDateTime.now().isBefore(token.getExpiresAt().minusMinutes(5))) {
      return token.getAccessToken();
    }

    WebClient webClient = webClientBuilder.build();
    Map<String, Object> response =
        webClient
            .post()
            .uri(properties.getTokenUrl())
            .bodyValue(
                Map.of(
                    "refresh_token", token.getRefreshToken(),
                    "client_id", properties.getClientId(),
                    "client_secret", properties.getClientSecret(),
                    "grant_type", "refresh_token"))
            .retrieve()
            .bodyToMono(Map.class)
            .block();

    if (Objects.isNull(response)) {
      throw new RuntimeException("Failed to refresh Google Calendar token");
    }

    String newAccessToken = (String) response.get("access_token");
    Number expiresIn = (Number) response.get("expires_in");

    token.setAccessToken(newAccessToken);
    token.setExpiresAt(OffsetDateTime.now().plusSeconds(expiresIn.longValue()));
    tokenRepository.save(token);

    return newAccessToken;
  }
}
