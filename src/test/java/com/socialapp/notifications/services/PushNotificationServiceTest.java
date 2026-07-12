package com.socialapp.notifications.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import com.socialapp.notifications.config.OneSignalProperties;

import reactor.core.publisher.Mono;

/**
 * Component (unit) tests for {@link PushNotificationService}, per ISTQB CTFL v4.0.1 (Section
 * 2.2.1 component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3
 * BDD Given/When/Then) — see {@code PostServiceTest} for the full rationale.
 *
 * <p>Unlike every other service tested in this project, {@code PushNotificationService} builds
 * its own {@link WebClient} internally in its constructor from {@link OneSignalProperties}
 * instead of receiving one via dependency injection (contrast with {@code MomoService}, which
 * gets its {@code WebClient} injected via a {@code @Qualifier}-annotated constructor parameter
 * backed by a {@code @Configuration} bean). There is no constructor seam to substitute a mock, so
 * the private final {@code webClient} field is replaced via reflection after construction — the
 * only way to keep this a true, network-free component test without changing production code.
 */
@ExtendWith(MockitoExtension.class)
class PushNotificationServiceTest {

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
  private ArgumentCaptor<Map> stubWebClientResponse(Mono responseMono) {
    WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
    WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
    WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);

    when(webClient.post()).thenReturn(uriSpec);
    when(uriSpec.uri(anyString())).thenReturn(bodySpec);
    when(bodySpec.bodyValue(bodyCaptor.capture())).thenReturn(headersSpec);
    when(headersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.bodyToMono(Map.class)).thenReturn(responseMono);
    return bodyCaptor;
  }

  // =====================================================================
  // sendToPlayer
  // =====================================================================

  @Nested
  @DisplayName("sendToPlayer")
  class SendToPlayerTests {

    @Test
    @DisplayName("should skip sending when the player id is null")
    void shouldSkipSend_whenPlayerIdIsNull() {
      // When
      pushNotificationService.sendToPlayer(null, "Title", "Body", Map.of());

      // Then
      verify(webClient, never()).post();
    }

    @Test
    @DisplayName("should skip sending when the player id is blank")
    void shouldSkipSend_whenPlayerIdIsBlank() {
      // When
      pushNotificationService.sendToPlayer("   ", "Title", "Body", Map.of());

      // Then
      verify(webClient, never()).post();
    }

    @Test
    @DisplayName("should send to that single player when the id is present")
    void shouldSend_whenPlayerIdValid() {
      // Given
      ArgumentCaptor<Map> bodyCaptor = stubWebClientResponse(Mono.just(Map.of("id", "notif-1")));

      // When
      pushNotificationService.sendToPlayer("player-1", "Title", "Body", Map.of());

      // Then
      assertThat(bodyCaptor.getValue().get("include_player_ids")).isEqualTo(List.of("player-1"));
    }
  }

  // =====================================================================
  // sendToPlayers
  // =====================================================================

  @Nested
  @DisplayName("sendToPlayers")
  class SendToPlayersTests {

    @Test
    @DisplayName("should skip sending when the player id list is empty")
    void shouldSkipSend_whenPlayerIdsIsEmpty() {
      // When
      pushNotificationService.sendToPlayers(List.of(), "Title", "Body", Map.of());

      // Then
      verify(webClient, never()).post();
    }

    @Test
    @DisplayName("should post the payload with the provided player ids")
    void shouldSendSuccessfully_whenPlayerIdsPresent() {
      // Given
      ArgumentCaptor<Map> bodyCaptor = stubWebClientResponse(Mono.just(Map.of("id", "notif-1")));

      // When
      pushNotificationService.sendToPlayers(List.of("p1", "p2"), "Title", "Body", Map.of("k", "v"));

      // Then
      assertThat(bodyCaptor.getValue().get("include_player_ids")).isEqualTo(List.of("p1", "p2"));
      assertThat(bodyCaptor.getValue().get("data")).isEqualTo(Map.of("k", "v"));
    }

    @Test
    @DisplayName("should default to an empty data map when data is null")
    void shouldUseEmptyDataMap_whenDataIsNull() {
      // Given
      ArgumentCaptor<Map> bodyCaptor = stubWebClientResponse(Mono.just(Map.of("id", "notif-1")));

      // When
      pushNotificationService.sendToPlayers(List.of("p1"), "Title", "Body", null);

      // Then
      assertThat(bodyCaptor.getValue().get("data")).isEqualTo(Map.of());
    }

    @Test
    @DisplayName("should swallow the error and not propagate when the request fails")
    void shouldSwallowException_whenSendFails() {
      // Given
      stubWebClientResponse(Mono.error(new RuntimeException("network down")));

      // When / Then
      assertThatCode(
              () -> pushNotificationService.sendToPlayers(List.of("p1"), "Title", "Body", Map.of()))
          .doesNotThrowAnyException();
    }
  }

  // =====================================================================
  // sendToAll
  // =====================================================================

  @Nested
  @DisplayName("sendToAll")
  class SendToAllTests {

    @Test
    @DisplayName("should post a broadcast payload to all segments")
    void shouldSendToAllSuccessfully() {
      // Given
      ArgumentCaptor<Map> bodyCaptor = stubWebClientResponse(Mono.just(Map.of("id", "notif-1")));

      // When
      pushNotificationService.sendToAll("Title", "Body", Map.of());

      // Then
      assertThat(bodyCaptor.getValue().get("included_segments")).isEqualTo(List.of("All"));
    }

    @Test
    @DisplayName("should swallow the error and not propagate when the broadcast fails")
    void shouldSwallowException_whenBroadcastFails() {
      // Given
      stubWebClientResponse(Mono.error(new RuntimeException("network down")));

      // When / Then
      assertThatCode(() -> pushNotificationService.sendToAll("Title", "Body", Map.of()))
          .doesNotThrowAnyException();
    }
  }
}
