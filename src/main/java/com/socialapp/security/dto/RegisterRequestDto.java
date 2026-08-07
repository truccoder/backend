package com.socialapp.security.dto;

import com.socialapp.security.util.UsernameSlugger;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param username the handle the profile URL is built from ({@code /u/{username}}), and therefore
 *     part of the user's identity on the platform rather than a display detail.
 *     <p><b>Optional on purpose.</b> Making it required would have been the cleaner contract, but
 *     it would also 422 every client that already sends the old three-field body — the frontend's
 *     signup form included — on the day this ships. Omitted, the server derives a handle from the
 *     full name (see {@code AuthService#assignUsername}); sent, the user gets the handle they
 *     picked. The frontend can add the field whenever it is ready, and nothing breaks in between.
 */
public record RegisterRequestDto(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 6) String password,
    @NotBlank String fullname,
    @Pattern(
            regexp = UsernameSlugger.USERNAME_PATTERN,
            message =
                "Username must be 3-30 characters of lowercase letters, digits or hyphens, and"
                    + " must not start with a hyphen")
        String username) {}
