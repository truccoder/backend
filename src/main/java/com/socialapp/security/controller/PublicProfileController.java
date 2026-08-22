package com.socialapp.security.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.security.dto.PublicProfileResponse;
import com.socialapp.security.service.ProfileService;

import lombok.RequiredArgsConstructor;

/**
 * Somebody else's profile.
 *
 * <p>Separate from {@link ProfileController} because the two differ in who the subject is, not
 * just in path: everything on {@code /v1/api/profile} acts on the caller and may return their
 * private fields, everything here is about a third party and must not.
 *
 * <p><b>Keyed by username, while its sibling endpoints are keyed by id</b> ({@code
 * /users/{userId}/posts}, {@code /users/{userId}/reputation}, {@code /github/stats/{userId}}).
 * That looks inconsistent and is deliberate: this is the entry point a stranger reaches from a
 * shared {@code /u/{username}} link, and routing the entry point by id would let anyone walk the
 * user table by counting. The response carries {@code id} so the client resolves handle → id
 * exactly once here and uses the id for every other section of the profile.
 *
 * <p>Open to guests (see {@code SecurityConfig}), which is why the response type matters more
 * here than anywhere else: {@link PublicProfileResponse} has no email, no role and no verification
 * flag, and this endpoint is now readable by the whole internet.
 *
 * <p>It returns its own response type rather than the shared {@code PublicUserResponse} because
 * the profile carries the reputation level and the verified-skill strip that the other six users
 * of that record must not pay for; the two types share the same privacy boundary, not the same
 * shape.
 */
@RestController
@RequestMapping("/v1/api/users/{username}/profile")
@RequiredArgsConstructor
public class PublicProfileController {
  private final ProfileService profileService;

  @GetMapping
  public PublicProfileResponse getPublicProfile(@PathVariable String username) {
    return profileService.getPublicProfile(username);
  }
}
