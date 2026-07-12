package com.socialapp.notifications.services;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.socialapp.notifications.config.OneSignalProperties;

import reactor.core.publisher.Mono;

/**
 * Stage 5 (Experience-based Testing) — Error Guessing technique per ISTQB CTFL v4.0.1 Section
 * 4.5.3, applied to {@link PushNotificationService}.
 *
 * <p>Same intentional resilience contract confirmed for {@code MailService} (see {@code
 * MailServiceErrorGuessingTest}) applies here: a failed push notification must never propagate
 * and fail the caller (usually {@code NotificationService.send()}, itself {@code @Async}). {@code
 * sendToPlayers}/{@code sendToAll} already catch-and-log every {@link Exception}; {@code
 * PushNotificationServiceTest} covers this with one generic {@link RuntimeException}. This class
 * hunts the concrete failure shapes a real OneSignal HTTP call produces via {@link WebClient}:
 * 4xx/5xx status responses ({@link WebClientResponseException}) and connection-level failures
 * ({@link WebClientRequestException}, e.g. timeout/refused wrapping a checked {@link
 * java.io.IOException}), plus a null-message worst case.
 *
 * <p>{@link WebClient} is mocked — no real HTTP call ever leaves the JVM.
 */
@ExtendWith(MockitoExtension.class)
class PushNotificationServiceErrorGuessingTest {

  private PushNotificationService pushNotificationService;
  private WebClient webClient;

  @BeforeEach
  void setUp() throws Exception {
    OneSignalProperties properties = new OneSignalProperties();
    properties.setAppId("app-id");
    properties.setRestApiKey("rest-key");
    properties.setBaseUrl("https://onesignal.example/api/v1");

    pushNotificationService = new PushNotificationService(properties);

    webClient = mock(WebClient.class);
    Field field = PushNotificationService.class.getDeclaredField("webClient");
    field.setAccessible(true);
    field.set(pushNotificationService, webClient);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void stubWebClientResponse(Mono responseMono) {
    WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
    WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
    WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

    when(webClient.post()).thenReturn(uriSpec);
    when(uriSpec.uri(anyString())).thenReturn(bodySpec);
    when(bodySpec.bodyValue(org.mockito.ArgumentMatchers.any())).thenReturn(headersSpec);
    when(headersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.bodyToMono(Map.class)).thenReturn(responseMono);
  }

  @Nested
  @DisplayName("OneSignal HTTP outages")
  class OneSignalOutageTests {

    @Test
    @DisplayName("shouldNotThrow_whenOneSignalRejectsWith400")
    void shouldNotThrow_whenOneSignalRejectsWith400() {
      // Given — malformed payload / invalid app_id rejected by OneSignal itself
      stubWebClientResponse(
          Mono.error(
              WebClientResponseException.create(
                  400,
                  "Bad Request",
                  HttpHeaders.EMPTY,
                  "{\"errors\":[\"Invalid app_id\"]}".getBytes(),
                  null)));

      // When / Then — a single failed push must never fail the caller
      assertThatCode(
              () ->
                  pushNotificationService.sendToPlayers(
                      List.of("player-1"), "Title", "Body", Map.of()))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("shouldNotThrow_whenOneSignalReturns500")
    void shouldNotThrow_whenOneSignalReturns500() {
      // Given — OneSignal-side outage
      stubWebClientResponse(
          Mono.error(
              WebClientResponseException.create(
                  500, "Internal Server Error", HttpHeaders.EMPTY, new byte[0], null)));

      // When / Then
      assertThatCode(() -> pushNotificationService.sendToAll("Title", "Body", Map.of()))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("shouldNotThrow_whenConnectionTimesOut")
    void shouldNotThrow_whenConnectionTimesOut() {
      // Given — OneSignal host accepted the connection but never responded in time
      stubWebClientResponse(
          Mono.error(
              new WebClientRequestException(
                  new java.net.SocketTimeoutException("Read timed out"),
                  HttpMethod.POST,
                  URI.create("https://onesignal.example/api/v1/notifications"),
                  new HttpHeaders())));

      // When / Then
      assertThatCode(
              () ->
                  pushNotificationService.sendToPlayers(
                      List.of("player-1"), "Title", "Body", Map.of()))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("shouldNotThrow_whenConnectionIsRefused")
    void shouldNotThrow_whenConnectionIsRefused() {
      // Given — OneSignal host unreachable
      stubWebClientResponse(
          Mono.error(
              new WebClientRequestException(
                  new java.net.ConnectException("Connection refused"),
                  HttpMethod.POST,
                  URI.create("https://onesignal.example/api/v1/notifications"),
                  new HttpHeaders())));

      // When / Then
      assertThatCode(() -> pushNotificationService.sendToAll("Title", "Body", Map.of()))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("shouldNotThrowNullPointerException_whenExceptionHasNullMessage")
    void shouldNotThrowNullPointerException_whenExceptionHasNullMessage() {
      // Given — worst-case third-party exception carrying no message at all; the service logs
      // e.getMessage() directly, which must not itself NPE
      stubWebClientResponse(Mono.error(new RuntimeException((String) null)));

      // When / Then
      assertThatCode(
              () ->
                  pushNotificationService.sendToPlayers(
                      List.of("player-1"), "Title", "Body", Map.of()))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("shouldNotThrow_whenOneSignalRejectsSingleRecipientPush")
    void shouldNotThrow_whenOneSignalRejectsSingleRecipientPush() {
      // Given — same outage, exercised through the sendToPlayer(single-id) entry point rather
      // than sendToPlayers directly, since it's the one NotificationService.send() actually calls
      stubWebClientResponse(
          Mono.error(
              WebClientResponseException.create(
                  429, "Too Many Requests", HttpHeaders.EMPTY, new byte[0], null)));

      // When / Then
      assertThatCode(
              () -> pushNotificationService.sendToPlayer("player-1", "Title", "Body", Map.of()))
          .doesNotThrowAnyException();
    }
  }
}
