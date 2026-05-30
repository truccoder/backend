package com.socialapp.security.dto;

public record AuthResponseDto(
    String accessToken, String refreshToken, String tokenType, long expiresIn) {}
