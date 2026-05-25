package com.socialapp.security.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequestDto(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 6) String password,
    @NotBlank String fullname,
    String profilePictureUrl) {}
