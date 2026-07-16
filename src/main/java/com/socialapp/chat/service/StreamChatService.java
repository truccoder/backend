package com.socialapp.chat.service;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
      log.warn("Stream API secret is not configured. Returning dummy token.");
      return "dummy-token-because-secret-is-missing";
    }

    try {
      SecretKey key = Keys.hmacShaKeyFor(apiSecret.getBytes(StandardCharsets.UTF_8));

      return Jwts.builder().claim("user_id", String.valueOf(userId)).signWith(key).compact();
    } catch (Exception e) {
      log.error("Failed to generate Stream chat token", e);
      throw new RuntimeException("Could not generate chat token", e);
    }
  }
}
