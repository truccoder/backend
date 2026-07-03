package com.socialapp.security.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.security.dto.*;
import com.socialapp.security.service.AuthService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/v1/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {
  private final AuthService authService;

  @PostMapping("/register")
  public void register(@Valid @RequestBody RegisterRequestDto request) {
    log.info(">>>> Register endpoint called with request: {}", request);
    authService.register(request);
  }

  @PostMapping("/login")
  public AuthResponseDto login(@Valid @RequestBody LoginRequestDto request) {
    log.info(">>>> Login endpoint called");
    return authService.login(request);
  }

  @PostMapping("/refresh")
  public AuthResponseDto refresh(@Valid @RequestBody RefreshTokenRequestDto request) {
    return authService.refresh(request);
  }

  @PostMapping("/forgot-password")
  public void forgotPassword(@Valid @RequestBody ForgotPasswordRequestDto request) {
    authService.forgotPassword(request);
  }

  @PostMapping("/reset-password")
  public void resetPassword(@Valid @RequestBody ResetPasswordRequestDto request) {
    authService.resetPassword(request);
  }

  @PostMapping("/verify-email")
  public void verifyEmail(@Valid @RequestBody VerifyEmailRequestDto request) {
    authService.verifyEmail(request);
  }

  @PostMapping("/magic-link")
  public void requestMagicLink(@Valid @RequestBody MagicLinkRequestDto request) {
    authService.requestMagicLink(request);
  }

  @PostMapping("/magic-link/login")
  public AuthResponseDto loginWithMagicLink(@Valid @RequestBody MagicLinkLoginRequestDto request) {
    return authService.loginWithMagicLink(request);
  }
}
