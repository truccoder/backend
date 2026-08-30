package com.socialapp.posts.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.socialapp.common.exception.ExternalApiException;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
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
  private final StringRedisTemplate redisTemplate;

  private static final String OAUTH_STATE_PREFIX = "gcal:oauth:state:";

  // Long enough for a human to finish Google's consent screen, short enough that a nonce
  // captured from browser history or a proxy log is worthless by the time it is replayed.
  private static final Duration OAUTH_STATE_TTL = Duration.ofMinutes(10);

  // 32 bytes = 256 bits of entropy. The whole security of the callback rests on this value
  // being unguessable, so it comes from SecureRandom, never from Random or a UUID v4 string.
  private static final int OAUTH_STATE_BYTES = 32;

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  /**
   * Lifetime assumed when Google's token response omits {@code expires_in}.
   *
   * <p>The field is documented as always present and in practice always is, so the old code read
   * it straight into {@code expiresIn.longValue()} — an NPE, and a 500, the one time it is not.
   * Google's own default for an access token is an hour; assuming that just means the next
   * refresh happens on schedule instead of the connection breaking outright.
   */
  private static final long DEFAULT_TOKEN_LIFETIME_SECONDS = 3600L;

  public String getAuthorizationUrl(Integer userId) {
    return UriComponentsBuilder.fromHttpUrl(properties.getAuthUrl())
        .queryParam("client_id", properties.getClientId())
        .queryParam("redirect_uri", properties.getRedirectUri())
        .queryParam("response_type", "code")
        .queryParam("scope", properties.getScope())
        .queryParam("access_type", "offline")
        .queryParam("prompt", "consent")
        .queryParam("state", issueOAuthState(userId))
        .build()
        .toUriString();
  }

  /**
   * Mints a single-use CSRF nonce for the OAuth round-trip and remembers which user it belongs
   * to.
   *
   * <p>The {@code state} param used to be {@code String.valueOf(userId)}. Because the callback is
   * {@code permitAll} (Google redirects the browser there with no bearer token of ours), anyone
   * could call it with {@code state=<victim id>} plus an OAuth {@code code} minted for their own
   * Google account and have the victim's row in {@code t_google_calendar_tokens} overwritten with
   * the attacker's tokens — an account-takeover of the calendar link. The id must therefore never
   * travel in a parameter the caller controls: only the server can map nonce back to user.
   */
  private String issueOAuthState(Integer userId) {
    byte[] nonce = new byte[OAUTH_STATE_BYTES];
    SECURE_RANDOM.nextBytes(nonce);
    String state = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);

    redisTemplate
        .opsForValue()
        .set(OAUTH_STATE_PREFIX + state, String.valueOf(userId), OAUTH_STATE_TTL);

    return state;
  }

  /**
   * Redeems a nonce issued by {@link #issueOAuthState} and returns the user it was issued to.
   *
   * <p>GETDEL is atomic, which is what makes the nonce genuinely single-use: two concurrent
   * callbacks carrying the same state cannot both win. A miss means the nonce was never issued,
   * already spent, or expired — all three are indistinguishable to the caller on purpose, so this
   * endpoint leaks nothing about which user ids exist.
   */
  public Integer consumeOAuthState(String state) {
    String userId = redisTemplate.opsForValue().getAndDelete(OAUTH_STATE_PREFIX + state);

    if (Objects.isNull(userId)) {
      log.warn("Rejected Google Calendar callback with unknown or expired OAuth state");
      throw new ForbiddenException("Invalid or expired OAuth state");
    }

    return Integer.valueOf(userId);
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
      throw new ExternalApiException("Failed to exchange OAuth code for tokens");
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
    entity.setExpiresAt(OffsetDateTime.now().plusSeconds(secondsUntilExpiry(expiresIn)));
    tokenRepository.save(entity);

    log.info("Google Calendar connected for user {}", userId);
  }

  public void addEventToCalendar(Integer userId, EventDetails event) {
    GoogleCalendarTokenEntity token =
        tokenRepository
            .findByUserId(userId)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "Google Calendar not connected. Please authorize first."));

    String accessToken = getValidAccessToken(token);

    // Map.of rejects a null value, so an event row missing a title or either timestamp — one
    // written before validateEventDetails existed — crashed here rather than being reported.
    if (Objects.isNull(event.getEventTitle())
        || Objects.isNull(event.getStartTime())
        || Objects.isNull(event.getEndTime())) {
      throw new ValidationException(
          "This event is missing a title or its start/end time, so it cannot be added to a"
              + " calendar.");
    }

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
    // A null expiry means we cannot tell whether the token is still good; refreshing is the
    // safe reading, and cheap. Reading it straight was an NPE on any row written before the
    // column was populated.
    if (Objects.nonNull(token.getExpiresAt())
        && OffsetDateTime.now().isBefore(token.getExpiresAt().minusMinutes(5))) {
      return token.getAccessToken();
    }

    // Google only returns a refresh_token on the first consent, and the exchange above declines
    // to overwrite a stored one with null — so a row can legitimately reach here without one.
    // Map.of throws NullPointerException on a null value, which turned "you need to reconnect"
    // into a 500 with no hint of what to do about it.
    if (Objects.isNull(token.getRefreshToken())) {
      throw new NotFoundException(
          "Google Calendar authorization has expired. Please reconnect your account.");
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
      throw new ExternalApiException("Failed to refresh Google Calendar token");
    }

    String newAccessToken = (String) response.get("access_token");
    Number expiresIn = (Number) response.get("expires_in");

    token.setAccessToken(newAccessToken);
    token.setExpiresAt(OffsetDateTime.now().plusSeconds(secondsUntilExpiry(expiresIn)));
    tokenRepository.save(token);

    return newAccessToken;
  }

  /** {@code expires_in} from a Google token response, or the assumed default when it is absent. */
  private static long secondsUntilExpiry(Number expiresIn) {
    return Objects.isNull(expiresIn) ? DEFAULT_TOKEN_LIFETIME_SECONDS : expiresIn.longValue();
  }
}
