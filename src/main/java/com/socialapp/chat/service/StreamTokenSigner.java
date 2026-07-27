package com.socialapp.chat.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import org.springframework.stereotype.Component;

import com.socialapp.chat.config.StreamChatProperties;
import com.socialapp.common.exception.MissingConfigurationException;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Signs the two kinds of JWT Stream expects: a per-user token the browser presents to {@code
 * connectUser}, and a server token the backend presents when calling Stream's REST API.
 *
 * <p>Both are HS256 <em>explicitly</em>. jjwt's single-argument {@code signWith(key)} picks the
 * strongest algorithm the key length allows, so a 64-character Stream secret (512 bits) would be
 * signed HS512 — which Stream rejects at {@code connectUser} even though every other part of the
 * token is correct.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StreamTokenSigner {

  private final StreamChatProperties properties;

  public String userToken(Integer userId, Instant issuedAt, Instant expiresAt) {
    return sign(
        Jwts.builder()
            .claim("user_id", String.valueOf(userId))
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(expiresAt)));
  }

  /** Server-side credential for Stream's REST API; {@code server: true} grants admin scope. */
  public String serverToken() {
    return sign(Jwts.builder().claim("server", true));
  }

  private String sign(JwtBuilder builder) {
    String apiSecret = properties.getApiSecret();
    if (apiSecret == null || apiSecret.isBlank()) {
      throw new MissingConfigurationException(
          "Stream Chat is not configured (missing stream.chat.api-secret); cannot issue a chat"
              + " token");
    }

    try {
      SecretKey key = Keys.hmacShaKeyFor(apiSecret.getBytes(StandardCharsets.UTF_8));
      return builder.signWith(key, Jwts.SIG.HS256).compact();
    } catch (Exception e) {
      log.error("Failed to sign Stream chat token", e);
      // Same root cause as the check above: an operator-configured secret that's unusable (e.g.
      // too short for HMAC signing) rather than merely unset — still a config problem, not a bug.
      throw new MissingConfigurationException("Could not generate chat token", e);
    }
  }
}
