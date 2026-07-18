package com.socialapp.chat.service;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.socialapp.common.exception.MissingConfigurationException;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class StreamChatService {

  @Value("${stream.chat.api-secret:}")
  private String apiSecret;

  public String generateUserToken(Integer userId) {
    if (apiSecret == null || apiSecret.isBlank()) {
      throw new MissingConfigurationException(
          "Stream Chat is not configured (missing stream.chat.api-secret); cannot issue a chat"
              + " token");
    }

    try {
      SecretKey key = Keys.hmacShaKeyFor(apiSecret.getBytes(StandardCharsets.UTF_8));

      return Jwts.builder().claim("user_id", String.valueOf(userId)).signWith(key).compact();
    } catch (Exception e) {
      log.error("Failed to generate Stream chat token", e);
      // Same root cause as the check above: an operator-configured secret that's unusable (e.g.
      // too short for HMAC signing) rather than merely unset — still a config problem, not a bug.
      throw new MissingConfigurationException("Could not generate chat token", e);
    }
  }
}
