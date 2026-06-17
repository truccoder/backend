package com.socialapp.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequestDto(
    @NotBlank String token, @NotBlank @Size(min = 6) String newPassword) {}
