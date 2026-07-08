package com.socialapp.security.dto;

import java.time.OffsetDateTime;

import com.socialapp.security.entity.UserRole;

public record UserResponse(
    Integer id,
    String email,
    String username,
    String fullName,
    String profilePictureUrl,
    boolean emailVerified,
    UserRole role,
    OffsetDateTime createdAt) {}
