package com.socialapp.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.socialapp.common.exception.MissingConfigurationException;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Component (unit) tests for {@link StreamChatService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing; Section 4.2.1 equivalence partitioning over the missing/weak/valid
 * {@code stream.chat.api-secret} partitions; Section 4.3.2 branch testing over the
 * {@code apiSecret == null || apiSecret.isBlank()} short-circuit).
 */
class StreamChatServiceTest {

  private static final Integer USER_ID = 42;

  private static StreamChatService serviceWithSecret(String apiSecret) {
    StreamChatService service = new StreamChatService();
    ReflectionTestUtils.setField(service, "apiSecret", apiSecret);
    return service;
  }

  @Nested
  @DisplayName("generateUserToken")
  class GenerateUserTokenTests {

    @Test
    @DisplayName("should reject when the api-secret is null")
    void shouldThrowMissingConfigurationException_whenSecretIsNull() {
      // Given
      StreamChatService service = serviceWithSecret(null);

      // When / Then
      assertThatThrownBy(() -> service.generateUserToken(USER_ID))
          .isInstanceOf(MissingConfigurationException.class);
    }

    @Test
    @DisplayName("should reject when the api-secret is blank (whitespace-only)")
    void shouldThrowMissingConfigurationException_whenSecretIsBlank() {
      // Given: exercises the second operand of the null-check "||" — the secret is non-null but
      // still rejected because isBlank() (not merely isEmpty()) is used.
      StreamChatService service = serviceWithSecret("   ");

      // When / Then
      assertThatThrownBy(() -> service.generateUserToken(USER_ID))
          .isInstanceOf(MissingConfigurationException.class);
    }

    @Test
    @DisplayName("should wrap a signing failure as a MissingConfigurationException")
    void shouldWrapSigningFailure_whenSecretIsTooWeakForHmacSha256() {
      // Given: HS256 requires a key of at least 256 bits; a short secret makes
      // Keys.hmacShaKeyFor(...) throw, exercising the try/catch branch for real rather than
      // via a mock.
      StreamChatService service = serviceWithSecret("too-short");

      // When / Then
      assertThatThrownBy(() -> service.generateUserToken(USER_ID))
          .isInstanceOf(MissingConfigurationException.class)
          .hasMessageContaining("Could not generate chat token");
    }

    @Test
    @DisplayName("should issue a token carrying the user id when the secret is configured")
    void shouldIssueToken_whenSecretIsConfigured() {
      // Given
      String secret = "a-sufficiently-long-stream-chat-api-secret-for-hs256";
      StreamChatService service = serviceWithSecret(secret);

      // When
      String token = service.generateUserToken(USER_ID);

      // Then
      assertThat(token).isNotBlank();
      String userIdClaim =
          Jwts.parser()
              .verifyWith(
                  Keys.hmacShaKeyFor(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
              .build()
              .parseSignedClaims(token)
              .getPayload()
              .get("user_id", String.class);
      assertThat(userIdClaim).isEqualTo(String.valueOf(USER_ID));
    }
  }
}
