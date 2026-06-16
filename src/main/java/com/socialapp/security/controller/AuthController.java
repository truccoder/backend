package com.socialapp.security.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.security.dto.ForgotPasswordRequestDto;
import com.socialapp.security.dto.LoginRequestDto;
import com.socialapp.security.dto.RefreshTokenRequest;
import com.socialapp.security.dto.RegisterRequestDto;
import com.socialapp.security.service.AuthService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/auth")
@RequiredArgsConstructor
public class AuthController {
  private final AuthService authService;

  @PostMapping("/register")
  public void register(@Valid @RequestBody RegisterRequestDto request) {
    authService.register(request);
  }

  @PostMapping("/login")
  public void login(@Valid @RequestBody LoginRequestDto request) {
    authService.login(request);
  }

  @PostMapping("/refresh")
  public void refresh(@Valid @RequestBody RefreshTokenRequest request) {
    authService.refresh(request);
  }

  @PostMapping("/forgot-password")
  public void forgotPassword(@Valid @RequestBody ForgotPasswordRequestDto request) {
    authService.forgotPassword(request);
  }
}
