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
}
