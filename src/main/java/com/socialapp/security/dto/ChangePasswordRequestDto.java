package com.socialapp.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequestDto(
    @NotBlank String currentPassword, @NotBlank @Size(min = 6) String newPassword) {}
