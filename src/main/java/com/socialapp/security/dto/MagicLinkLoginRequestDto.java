package com.socialapp.security.dto;

import jakarta.validation.constraints.NotBlank;

public record MagicLinkLoginRequestDto(@NotBlank String token) {}
