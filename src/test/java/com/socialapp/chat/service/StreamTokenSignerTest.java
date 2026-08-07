package com.socialapp.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.socialapp.chat.config.StreamChatProperties;
import com.socialapp.common.exception.MissingConfigurationException;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Component (unit) tests for {@link StreamTokenSigner}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing; Section 4.2.1 equivalence partitioning over the missing/weak/valid
 * {@code stream.chat.api-secret} partitions; Section 4.3.2 branch testing over the
 * {@code apiSecret == null || apiSecret.isBlank()} short-circuit).
 */
class StreamTokenSignerTest {

  private static final Integer USER_ID = 42;

  /**
   * 64 characters, matching the shape Stream actually issues. Long enough that jjwt's
   * algorithm-inference would pick HS512 — which is exactly the regression these tests guard.
   */
  private static final String REAL_LENGTH_SECRET =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  private static StreamTokenSigner signerWithSecret(String apiSecret) {
    StreamChatProperties properties = new StreamChatProperties();
    properties.setApiKey("test-api-key");
    properties.setApiSecret(apiSecret);
    return new StreamTokenSigner(properties);
  }

  private static String headerOf(String token) {
    String encodedHeader = token.split("\\.")[0];
    return new String(Base64.getUrlDecoder().decode(encodedHeader), StandardCharsets.UTF_8);
  }

  private static Claims claimsOf(String token, String secret) {
    return Jwts.parser()
        .verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
        .build()
        .parseSignedClaims(token)
        .getPayload();
  }

  @Nested
  @DisplayName("userToken")
  class UserTokenTests {

    @Test
    @DisplayName("should reject when the api-secret is null")
    void shouldThrowMissingConfigurationException_whenSecretIsNull() {
      // Given
      StreamTokenSigner signer = signerWithSecret(null);

      // When / Then
      assertThatThrownBy(() -> signer.userToken(USER_ID, Instant.now(), Instant.now()))
          .isInstanceOf(MissingConfigurationException.class);
    }

    @Test
    @DisplayName("should reject when the api-secret is blank (whitespace-only)")
    void shouldThrowMissingConfigurationException_whenSecretIsBlank() {
      // Given: exercises the second operand of the null-check "||" — the secret is non-null but
      // still rejected because isBlank() (not merely isEmpty()) is used.
      StreamTokenSigner signer = signerWithSecret("   ");

      // When / Then
      assertThatThrownBy(() -> signer.userToken(USER_ID, Instant.now(), Instant.now()))
          .isInstanceOf(MissingConfigurationException.class);
    }

    @Test
    @DisplayName("should wrap a signing failure as a MissingConfigurationException")
    void shouldWrapSigningFailure_whenSecretIsTooWeakForHmacSha256() {
      // Given: HS256 requires a key of at least 256 bits; a short secret makes
      // Keys.hmacShaKeyFor(...) throw, exercising the try/catch branch for real rather than
      // via a mock.
      StreamTokenSigner signer = signerWithSecret("too-short");

      // When / Then
      assertThatThrownBy(() -> signer.userToken(USER_ID, Instant.now(), Instant.now()))
          .isInstanceOf(MissingConfigurationException.class)
          .hasMessageContaining("Could not generate chat token");
    }

    @Test
    @DisplayName("should sign with HS256 even when the secret is long enough to imply HS512")
    void shouldSignWithHs256_whenSecretIsStreamLength() {
      // Given: a 512-bit secret. jjwt's single-argument signWith(key) would infer HS512 here, and
      // Stream rejects anything but HS256 at connectUser.
      StreamTokenSigner signer = signerWithSecret(REAL_LENGTH_SECRET);

      // When
      String token = signer.userToken(USER_ID, Instant.now(), Instant.now().plusSeconds(60));

      // Then
      assertThat(headerOf(token)).contains("\"alg\":\"HS256\"");
    }

    @Test
    @DisplayName("should carry the user id plus iat/exp claims")
    void shouldIssueTokenWithUserIdAndLifetimeClaims() {
      // Given
      StreamTokenSigner signer = signerWithSecret(REAL_LENGTH_SECRET);
      Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
      Instant expiresAt = issuedAt.plus(24, ChronoUnit.HOURS);

      // When
      String token = signer.userToken(USER_ID, issuedAt, expiresAt);

      // Then
      Claims claims = claimsOf(token, REAL_LENGTH_SECRET);
      assertThat(claims.get("user_id", String.class)).isEqualTo(String.valueOf(USER_ID));
      assertThat(claims.getIssuedAt().toInstant()).isEqualTo(issuedAt);
      assertThat(claims.getExpiration().toInstant()).isEqualTo(expiresAt);
    }
  }

  @Nested
  @DisplayName("serverToken")
  class ServerTokenTests {

    @Test
    @DisplayName("should carry the server claim that grants admin scope on Stream's REST API")
    void shouldIssueServerScopedToken() {
      // Given
      StreamTokenSigner signer = signerWithSecret(REAL_LENGTH_SECRET);

      // When
      String token = signer.serverToken();

      // Then
      assertThat(headerOf(token)).contains("\"alg\":\"HS256\"");
      assertThat(claimsOf(token, REAL_LENGTH_SECRET).get("server", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("should reject when the api-secret is missing")
    void shouldThrowMissingConfigurationException_whenSecretIsMissing() {
      // Given
      StreamTokenSigner signer = signerWithSecret(null);

      // When / Then
      assertThatThrownBy(signer::serverToken).isInstanceOf(MissingConfigurationException.class);
    }
  }
}
