package com.socialapp.security.dto;

public record AuthResponse(
    String accessToken, String refreshToken, String tokenType, long expiresIn) {}
