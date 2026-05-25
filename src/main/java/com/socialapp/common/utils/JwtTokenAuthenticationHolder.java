package com.socialapp.common.utils;

import lombok.experimental.UtilityClass;

@UtilityClass
public class JwtTokenAuthenticationHolder {
  //    public static Optional<JwtTokenUserDetailsEntity> findAuthenticatedUser() {
  //        return Optional.ofNullable(SecurityContextHolder.getContext())
  //                .map(SecurityContext::getAuthentication)
  //                .filter(Authentication::isAuthenticated)
  //                .map(Authentication::getPrincipal)
  //                .filter(principal -> principal instanceof JwtTokenUserDetailsEntity)
  //                .map(JwtTokenUserDetailsEntity.class::cast);
  //    }
  //
  //    public static JwtTokenUserDetailsEntity getAuthenticatedUser() {
  //        return findAuthenticatedUser()
  //                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Not
  // Authorized"));
  //    }
}
