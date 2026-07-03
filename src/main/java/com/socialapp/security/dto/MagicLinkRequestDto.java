package com.socialapp.security.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record MagicLinkRequestDto(@NotBlank @Email String email) {}
