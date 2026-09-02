package com.socialapp.security.dto;

import java.time.OffsetDateTime;

import com.socialapp.security.entity.UserRole;

/**
 * The signed-in user, as shown to themselves — carries {@code email} and {@code role}, which is
 * why it must never be returned for anybody else. See {@link PublicUserResponse} for that shape.
 *
 * @param coverImageUrl banner across the top of the profile, or null for a profile with no cover.
 *     Present on this record as well as on {@link PublicProfileResponse} because {@code /profile/me}
 *     is what the profile editor reads to show the user their current one.
 */
public record UserResponse(
    Integer id,
    String email,
    String username,
    String fullName,
    String profilePictureUrl,
    String coverImageUrl,
    boolean emailVerified,
    UserRole role,
    OffsetDateTime createdAt) {}
