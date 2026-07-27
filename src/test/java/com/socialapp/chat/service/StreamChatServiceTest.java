package com.socialapp.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.chat.client.StreamChatClient;
import com.socialapp.chat.config.StreamChatProperties;
import com.socialapp.chat.dto.ChatTokenResponse;
import com.socialapp.common.exception.ExternalApiException;
import com.socialapp.common.exception.MissingConfigurationException;
import com.socialapp.security.entity.UserEntity;

/**
 * Component (unit) tests for {@link StreamChatService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing; Section 4.2.1 equivalence partitioning over the configured/unconfigured
 * partitions; Section 4.3.2 branch testing over the best-effort profile-sync try/catch).
 */
@ExtendWith(MockitoExtension.class)
class StreamChatServiceTest {

  private static final String API_KEY = "stream-public-key";
  private static final String API_SECRET =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @Mock private StreamChatClient streamChatClient;

  private StreamChatProperties properties;
  private StreamChatService service;
  private UserEntity user;

  @BeforeEach
  void setUp() {
    properties = new StreamChatProperties();
    properties.setApiKey(API_KEY);
    properties.setApiSecret(API_SECRET);
    properties.setTokenTtl(Duration.ofHours(24));

    service =
        new StreamChatService(properties, new StreamTokenSigner(properties), streamChatClient);

    user = new UserEntity();
    user.setId(42);
    user.setFullName("Ada Lovelace");
  }

  @Nested
  @DisplayName("issueToken")
  class IssueTokenTests {

    @Test
    @DisplayName("should return the public api key alongside the token")
    void shouldReturnApiKey_soTheFrontendHasASingleSourceOfTruth() {
      // When
      ChatTokenResponse response = service.issueToken(user);

      // Then
      assertThat(response.getApiKey()).isEqualTo(API_KEY);
      assertThat(response.getUserId()).isEqualTo("42");
      assertThat(response.getStreamToken()).isNotBlank();
    }

    @Test
    @DisplayName("should return an expiry roughly one token-ttl into the future")
    void shouldReturnExpiresAt_derivedFromConfiguredTtl() {
      // Given
      Instant before = Instant.now();

      // When
      ChatTokenResponse response = service.issueToken(user);

      // Then
      assertThat(response.getExpiresAt())
          .isBetween(before.plus(Duration.ofHours(24)), Instant.now().plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("should push the user profile to Stream so the UI shows names, not numeric ids")
    void shouldUpsertUser() {
      // When
      service.issueToken(user);

      // Then
      then(streamChatClient).should().upsertUser(user);
    }

    @Test
    @DisplayName("should still issue a token when the profile sync fails")
    void shouldSwallowSyncFailure_becauseADegradedUiBeatsNoChatAtAll() {
      // Given
      willThrow(new ExternalApiException("Stream unreachable"))
          .given(streamChatClient)
          .upsertUser(any());

      // When / Then
      assertThatCode(() -> assertThat(service.issueToken(user).getStreamToken()).isNotBlank())
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should reject when the api-secret is missing")
    void shouldThrowMissingConfigurationException_whenSecretIsMissing() {
      // Given
      properties.setApiSecret("");

      // When / Then
      assertThatThrownBy(() -> service.issueToken(user))
          .isInstanceOf(MissingConfigurationException.class);
      then(streamChatClient).should(never()).upsertUser(any());
    }

    @Test
    @DisplayName("should reject when the api-key is missing")
    void shouldThrowMissingConfigurationException_whenKeyIsMissing() {
      // Given: a token signed against an app whose public key the frontend never receives is
      // unusable, so this fails loudly at the same 503 rather than returning a null apiKey.
      properties.setApiKey(null);

      // When / Then
      assertThatThrownBy(() -> service.issueToken(user))
          .isInstanceOf(MissingConfigurationException.class);
      then(streamChatClient).should(never()).upsertUser(any());
    }
  }
}
