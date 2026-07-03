package com.socialapp.security.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyEmailRequestDto(@NotBlank String token) {}
