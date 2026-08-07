package com.socialapp.security.util;

import java.util.Optional;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.socialapp.security.entity.UserEntity;

import lombok.experimental.UtilityClass;

@UtilityClass
public class SecurityUtils {

  public static Optional<UserEntity> getCurrentUser() {
    return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
        .filter(Authentication::isAuthenticated)
        .map(Authentication::getPrincipal)
        .filter(UserEntity.class::isInstance)
        .map(UserEntity.class::cast);
  }

  public static UserEntity requireCurrentUser() {
    return getCurrentUser()
        .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Not authenticated"));
  }

  public static Integer getCurrentUserId() {
    return requireCurrentUser().getId();
  }

  /**
   * The caller's id, or {@code null} when nobody is signed in.
   *
   * <p>For the endpoints that guests may read (the discovery feed, a public post, a public
   * profile's posts). {@link #getCurrentUserId} <em>throws</em> for an anonymous request, which is
   * the right behaviour everywhere it is used today — but on an endpoint that is deliberately open,
   * that throw turns a legitimate guest request into a 401 in a place Spring Security has already
   * decided to allow.
   *
   * <p>Callers must treat {@code null} as the "stranger" relationship level, never as an error —
   * see {@code PostVisibilityService}, which takes the null case explicitly rather than letting it
   * reach a repository as a null parameter.
   */
  public static Integer getCurrentUserIdOrNull() {
    return getCurrentUser().map(UserEntity::getId).orElse(null);
  }
}
