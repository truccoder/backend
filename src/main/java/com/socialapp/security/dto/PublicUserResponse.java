package com.socialapp.security.dto;

import java.time.OffsetDateTime;

import com.socialapp.security.entity.UserEntity;

/**
 * A user as anyone else is allowed to see them.
 *
 * <p>Deliberately <b>not</b> {@link UserResponse}, and this is a security boundary rather than a
 * tidiness preference: {@code UserResponse} carries {@code email}, {@code emailVerified} and {@code
 * role}. It is the right shape for {@code GET /v1/api/profile/me}, where the caller is the subject.
 * Reusing it for the public profile, the author of a post, or the list of people who reacted would
 * hand every reader the email address and admin flag of every user they can see — a mass
 * disclosure that no single endpoint would look guilty of.
 *
 * <p>Every endpoint that returns "some other user" should return this type, so the set of fields
 * that leave the server for a stranger stays in one place and can be reviewed as one decision.
 */
public record PublicUserResponse(
    Integer id,
    String username,
    String fullName,
    String profilePictureUrl,
    Integer eliteScore,
    OffsetDateTime createdAt) {

  public static PublicUserResponse from(UserEntity user) {
    return new PublicUserResponse(
        user.getId(),
        user.getUsername(),
        user.getFullName(),
        user.getProfilePictureUrl(),
        user.getEliteScore(),
        user.getCreatedAt());
  }
}
