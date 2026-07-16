package com.socialapp.security.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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

  @PostMapping(value = "/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public void register(
      @Valid @RequestPart("metadata") RegisterRequestDto request,
      @RequestPart(value = "profilePicture", required = false) MultipartFile profilePicture) {
    authService.register(request, profilePicture);
  }

  @PostMapping("/login")
  public AuthResponseDto login(@Valid @RequestBody LoginRequestDto request) {
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

  @PostMapping("/logout")
  public void logout(@Valid @RequestBody RefreshTokenRequestDto request) {
    authService.logout(request);
  }

  @GetMapping("/google/url")
  public OAuthUrlResponseDto getGoogleOAuthUrl() {
    return authService.getGoogleOAuthUrl();
  }

  @PostMapping("/google/callback")
  public AuthResponseDto loginWithGoogle(@Valid @RequestBody GoogleLoginRequestDto request) {
    return authService.loginWithGoogle(request.getCode());
  }

  @GetMapping("/github/url")
  public OAuthUrlResponseDto getGithubOAuthUrl() {
    return authService.getGithubOAuthUrl();
  }

  @PostMapping("/github/callback")
  public AuthResponseDto loginWithGithub(@Valid @RequestBody GithubLoginRequestDto request) {
    return authService.loginWithGithub(request.getCode());
  }
}
