package com.socialapp.common.utils;

import java.util.Optional;

import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.util.SecurityUtils;

import lombok.experimental.UtilityClass;

@UtilityClass
public class JwtTokenAuthenticationHolder {

  public static Optional<UserEntity> findAuthenticatedUser() {
    return SecurityUtils.getCurrentUser();
  }

  public static UserEntity getAuthenticatedUser() {
    return SecurityUtils.requireCurrentUser();
  }
}
