package com.socialapp.posts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.socialapp.common.exception.ExternalApiException;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.posts.config.GoogleCalendarProperties;
import com.socialapp.posts.entity.EventDetails;
import com.socialapp.posts.entity.GoogleCalendarTokenEntity;
import com.socialapp.posts.repository.GoogleCalendarTokenRepository;

import reactor.core.publisher.Mono;

/**
 * Component (unit) tests for {@link GoogleCalendarService}, per ISTQB CTFL v4.0.1 Section 2.2.1
 * (component testing) — see {@code MomoServiceTest}/{@code PushNotificationServiceTest} for the
 * established pattern of mocking {@link WebClient}'s fluent chain
 * ({@code post().uri().bodyValue().retrieve().bodyToMono()}) without any real network call.
 * {@code GoogleCalendarService} receives a {@code WebClient.Builder} (not a built {@code
 * WebClient}) and calls {@code .build()} fresh for every outbound call, so the builder itself is
 * mocked to always return one shared mock {@code WebClient}.
 */
@ExtendWith(MockitoExtension.class)
class GoogleCalendarServiceTest {

  private static final Integer USER_ID = 1;

  @Mock private GoogleCalendarTokenRepository tokenRepository;
  @Mock private WebClient.Builder webClientBuilder;
  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;

  private GoogleCalendarProperties properties;
  private GoogleCalendarService googleCalendarService;

  private WebClient webClient;
  private WebClient.RequestBodyUriSpec uriSpec;

  @BeforeEach
  void setUp() {
    properties = new GoogleCalendarProperties();
    properties.setClientId("client-id");
    properties.setClientSecret("client-secret");
    properties.setRedirectUri("https://app.example/oauth/callback");
    properties.setTokenUrl("https://oauth2.googleapis.com/token");
    properties.setAuthUrl("https://accounts.google.com/o/oauth2/v2/auth");
    properties.setCalendarApiUrl("https://www.googleapis.com/calendar/v3");
    properties.setScope("https://www.googleapis.com/auth/calendar.events");

    googleCalendarService =
        new GoogleCalendarService(properties, tokenRepository, webClientBuilder, redisTemplate);

    lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    webClient = mock(WebClient.class);
    uriSpec = mock(WebClient.RequestBodyUriSpec.class);
    lenient().when(webClientBuilder.build()).thenReturn(webClient);
    lenient().when(webClient.post()).thenReturn(uriSpec);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private ArgumentCaptor<Map> stubPost(String uri, boolean withAuthHeader, Mono responseMono) {
    WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
    WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);

    when(uriSpec.uri(uri)).thenReturn(bodySpec);
    if (withAuthHeader) {
      when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
    }
    when(bodySpec.bodyValue(bodyCaptor.capture())).thenReturn(headersSpec);
    when(headersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.bodyToMono(Map.class)).thenReturn(responseMono);
    return bodyCaptor;
  }

  private static EventDetails sampleEvent(String timezone) {
    EventDetails event = new EventDetails();
    event.setEventTitle("Tech Meetup");
    event.setEventDescription("A meetup");
    event.setLocation("Hanoi");
    event.setStartTime(OffsetDateTime.parse("2026-08-01T10:00:00Z"));
    event.setEndTime(OffsetDateTime.parse("2026-08-01T12:00:00Z"));
    event.setTimezone(timezone);
    return event;
  }

  // =====================================================================
  // getAuthorizationUrl
  // =====================================================================

  @Nested
  @DisplayName("getAuthorizationUrl")
  class GetAuthorizationUrlTests {

    @Test
    @DisplayName("should build the Google OAuth consent URL with an opaque nonce as state")
    void shouldBuildAuthorizationUrl_withOpaqueNonceAsState() {
      // When
      String url = googleCalendarService.getAuthorizationUrl(USER_ID);

      // Then
      assertThat(url)
          .startsWith("https://accounts.google.com/o/oauth2/v2/auth")
          .contains("client_id=client-id")
          .contains("redirect_uri=https://app.example/oauth/callback")
          .contains("response_type=code")
          .contains("access_type=offline")
          .contains("prompt=consent");

      // Then — the user id must NOT be discoverable in the URL the browser carries around;
      // leaking it there is what let an attacker forge a callback for someone else's account.
      assertThat(stateParamOf(url)).isNotEqualTo(String.valueOf(USER_ID));
    }

    @Test
    @DisplayName("should store the state nonce against the user id with a bounded TTL")
    void shouldStoreStateNonce_withTtl() {
      // When
      String url = googleCalendarService.getAuthorizationUrl(USER_ID);

      // Then
      verify(valueOperations)
          .set(
              "gcal:oauth:state:" + stateParamOf(url),
              String.valueOf(USER_ID),
              Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("should mint a different state on every call")
    void shouldMintDifferentStateEveryCall() {
      // When
      String first = stateParamOf(googleCalendarService.getAuthorizationUrl(USER_ID));
      String second = stateParamOf(googleCalendarService.getAuthorizationUrl(USER_ID));

      // Then — a reused nonce would be replayable across sessions
      assertThat(first).isNotEqualTo(second);
    }
  }

  // =====================================================================
  // consumeOAuthState
  // =====================================================================

  @Nested
  @DisplayName("consumeOAuthState")
  class ConsumeOAuthStateTests {

    @Test
    @DisplayName("should return the user id the nonce was issued to and burn the nonce")
    void shouldReturnUserId_andBurnNonce() {
      // Given
      when(valueOperations.getAndDelete("gcal:oauth:state:nonce-abc")).thenReturn("42");

      // When
      Integer userId = googleCalendarService.consumeOAuthState("nonce-abc");

      // Then — GETDEL, not GET: the nonce is spent by the act of reading it
      assertThat(userId).isEqualTo(42);
      verify(valueOperations).getAndDelete("gcal:oauth:state:nonce-abc");
    }

    @Test
    @DisplayName("should reject a guessed state such as a bare user id")
    void shouldRejectGuessedState() {
      // Given — nothing was ever issued under this value
      when(valueOperations.getAndDelete("gcal:oauth:state:9001")).thenReturn(null);

      // When / Then — this is the exact attack from B1: callback with state=<victim id>
      assertThatThrownBy(() -> googleCalendarService.consumeOAuthState("9001"))
          .isInstanceOf(ForbiddenException.class)
          .hasMessageContaining("Invalid or expired OAuth state");
    }

    @Test
    @DisplayName("should reject a nonce that was already redeemed")
    void shouldRejectAlreadyRedeemedNonce() {
      // Given — first redemption wins, second sees the key gone
      when(valueOperations.getAndDelete("gcal:oauth:state:nonce-abc"))
          .thenReturn("42")
          .thenReturn(null);

      // When
      googleCalendarService.consumeOAuthState("nonce-abc");

      // Then
      assertThatThrownBy(() -> googleCalendarService.consumeOAuthState("nonce-abc"))
          .isInstanceOf(ForbiddenException.class);
    }
  }

  private static String stateParamOf(String url) {
    return UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("state");
  }

  // =====================================================================
  // handleOAuthCallback
  // =====================================================================

  @Nested
  @DisplayName("handleOAuthCallback")
  class HandleOAuthCallbackTests {

    @Test
    @DisplayName("should create a new token entity when the user connects for the first time")
    void shouldCreateNewTokenEntity_whenUserConnectsForTheFirstTime() {
      // Given
      when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
      stubPost(
          properties.getTokenUrl(),
          false,
          Mono.just(
              Map.of(
                  "access_token", "new-access-token",
                  "refresh_token", "new-refresh-token",
                  "expires_in", 3600)));

      // When
      googleCalendarService.handleOAuthCallback(USER_ID, "auth-code");

      // Then
      ArgumentCaptor<GoogleCalendarTokenEntity> captor =
          ArgumentCaptor.forClass(GoogleCalendarTokenEntity.class);
      verify(tokenRepository).save(captor.capture());
      assertThat(captor.getValue().getAccessToken()).isEqualTo("new-access-token");
      assertThat(captor.getValue().getRefreshToken()).isEqualTo("new-refresh-token");
    }

    @Test
    @DisplayName("should keep the existing refresh token when Google omits it on re-consent")
    void shouldKeepExistingRefreshToken_whenResponseOmitsIt() {
      // Given — Google only returns a refresh_token on the very first consent
      GoogleCalendarTokenEntity existing =
          GoogleCalendarTokenEntity.builder()
              .userId(USER_ID)
              .accessToken("old-access-token")
              .refreshToken("existing-refresh-token")
              .build();
      when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
      stubPost(
          properties.getTokenUrl(),
          false,
          Mono.just(Map.of("access_token", "refreshed-access-token", "expires_in", 3600)));

      // When
      googleCalendarService.handleOAuthCallback(USER_ID, "auth-code");

      // Then
      ArgumentCaptor<GoogleCalendarTokenEntity> captor =
          ArgumentCaptor.forClass(GoogleCalendarTokenEntity.class);
      verify(tokenRepository).save(captor.capture());
      assertThat(captor.getValue().getAccessToken()).isEqualTo("refreshed-access-token");
      assertThat(captor.getValue().getRefreshToken()).isEqualTo("existing-refresh-token");
    }

    @Test
    @DisplayName("should throw ExternalApiException when Google's token endpoint returns nothing")
    void shouldThrowRuntimeException_whenTokenResponseIsNull() {
      // Given
      stubPost(properties.getTokenUrl(), false, Mono.empty());

      // When / Then
      assertThatThrownBy(() -> googleCalendarService.handleOAuthCallback(USER_ID, "auth-code"))
          .isInstanceOf(ExternalApiException.class)
          .hasMessageContaining("Failed to exchange OAuth code for tokens");
    }
  }

  // =====================================================================
  // addEventToCalendar
  // =====================================================================

  @Nested
  @DisplayName("addEventToCalendar")
  class AddEventToCalendarTests {

    @Test
    @DisplayName("should post the event using the existing token when it is not yet expiring")
    void shouldPostEvent_whenAccessTokenIsStillValid() {
      // Given
      GoogleCalendarTokenEntity token =
          GoogleCalendarTokenEntity.builder()
              .userId(USER_ID)
              .accessToken("valid-access-token")
              .expiresAt(OffsetDateTime.now().plusHours(1))
              .build();
      when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.of(token));
      ArgumentCaptor<Map> bodyCaptor =
          stubPost(
              properties.getCalendarApiUrl() + "/calendars/primary/events",
              true,
              Mono.just(Map.of("id", "event-1")));

      // When
      googleCalendarService.addEventToCalendar(USER_ID, sampleEvent("Asia/Bangkok"));

      // Then
      assertThat(bodyCaptor.getValue()).containsEntry("summary", "Tech Meetup");
      assertThat((Map<String, Object>) bodyCaptor.getValue().get("start"))
          .containsEntry("timeZone", "Asia/Bangkok");
      verify(tokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("should default to Asia/Ho_Chi_Minh when the event has no timezone set")
    void shouldDefaultTimezone_whenEventTimezoneIsNull() {
      // Given
      GoogleCalendarTokenEntity token =
          GoogleCalendarTokenEntity.builder()
              .userId(USER_ID)
              .accessToken("valid-access-token")
              .expiresAt(OffsetDateTime.now().plusHours(1))
              .build();
      when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.of(token));
      ArgumentCaptor<Map> bodyCaptor =
          stubPost(
              properties.getCalendarApiUrl() + "/calendars/primary/events",
              true,
              Mono.just(Map.of("id", "event-1")));

      // When
      googleCalendarService.addEventToCalendar(USER_ID, sampleEvent(null));

      // Then
      assertThat((Map<String, Object>) bodyCaptor.getValue().get("start"))
          .containsEntry("timeZone", "Asia/Ho_Chi_Minh");
    }

    @Test
    @DisplayName("should refresh the access token first when it is about to expire")
    void shouldRefreshAccessToken_whenItIsAboutToExpire() {
      // Given — expires in 1 minute, inside the 5-minute refresh buffer
      GoogleCalendarTokenEntity token =
          GoogleCalendarTokenEntity.builder()
              .userId(USER_ID)
              .accessToken("stale-access-token")
              .refreshToken("refresh-token")
              .expiresAt(OffsetDateTime.now().plusMinutes(1))
              .build();
      when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.of(token));
      stubPost(
          properties.getTokenUrl(),
          false,
          Mono.just(Map.of("access_token", "fresh-access-token", "expires_in", 3600)));
      ArgumentCaptor<Map> eventBodyCaptor =
          stubPost(
              properties.getCalendarApiUrl() + "/calendars/primary/events",
              true,
              Mono.just(Map.of("id", "event-1")));

      // When
      googleCalendarService.addEventToCalendar(USER_ID, sampleEvent("Asia/Bangkok"));

      // Then
      verify(tokenRepository).save(token);
      assertThat(token.getAccessToken()).isEqualTo("fresh-access-token");
      assertThat(eventBodyCaptor.getValue()).containsEntry("summary", "Tech Meetup");
    }

    @Test
    @DisplayName("should throw ExternalApiException when the token refresh call returns nothing")
    void shouldThrowRuntimeException_whenRefreshResponseIsNull() {
      // Given
      GoogleCalendarTokenEntity token =
          GoogleCalendarTokenEntity.builder()
              .userId(USER_ID)
              .accessToken("stale-access-token")
              .refreshToken("refresh-token")
              .expiresAt(OffsetDateTime.now().minusMinutes(1))
              .build();
      when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.of(token));
      stubPost(properties.getTokenUrl(), false, Mono.empty());

      // When / Then
      assertThatThrownBy(
              () -> googleCalendarService.addEventToCalendar(USER_ID, sampleEvent("Asia/Bangkok")))
          .isInstanceOf(ExternalApiException.class)
          .hasMessageContaining("Failed to refresh Google Calendar token");
    }

    @Test
    @DisplayName("should throw NotFoundException when the user has not connected Google Calendar")
    void shouldThrowRuntimeException_whenNotConnected() {
      // Given
      when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(
              () -> googleCalendarService.addEventToCalendar(USER_ID, sampleEvent("Asia/Bangkok")))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Google Calendar not connected");
    }
  }

  // =====================================================================
  // isConnected
  // =====================================================================

  @Nested
  @DisplayName("isConnected")
  class IsConnectedTests {

    @Test
    @DisplayName("should return true when a token exists for the user")
    void shouldReturnTrue_whenTokenExists() {
      // Given
      when(tokenRepository.findByUserId(USER_ID))
          .thenReturn(Optional.of(GoogleCalendarTokenEntity.builder().userId(USER_ID).build()));

      // When / Then
      assertThat(googleCalendarService.isConnected(USER_ID)).isTrue();
    }

    @Test
    @DisplayName("should return false when no token exists for the user")
    void shouldReturnFalse_whenNoTokenExists() {
      // Given
      when(tokenRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThat(googleCalendarService.isConnected(USER_ID)).isFalse();
    }
  }
}
