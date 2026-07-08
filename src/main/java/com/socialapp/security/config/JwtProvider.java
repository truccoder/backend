package com.socialapp.security.config;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JwtProvider {

  private final JwtProperties jwtProperties;

  public String generateAccessToken(String email, String jobTitle) {
    var builder =
        Jwts.builder()
            .subject(email)
            .issuedAt(new Date())
            .expiration(
                new Date(System.currentTimeMillis() + jwtProperties.accessTokenTtl().toMillis()));

    if (jobTitle != null) {
      builder.claim("jobTitle", jobTitle);
    }

    return builder.signWith(getSigningKey()).compact();
  }

  public String extractEmail(String token) {
    return extractClaim(token, Claims::getSubject);
  }

  public String extractJobTitle(String token) {
    return extractClaim(token, claims -> claims.get("jobTitle", String.class));
  }

  public boolean isTokenValid(String token) {
    try {
      Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
    Claims claims =
        Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
    return claimsResolver.apply(claims);
  }

  private SecretKey getSigningKey() {
    return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
  }
}
