package com.socialapp.security.controller;

import com.socialapp.security.dto.AuthResponse;
import com.socialapp.security.dto.LoginRequestDto;
import com.socialapp.security.dto.RefreshTokenRequest;
import com.socialapp.security.dto.RegisterRequestDto;
import com.socialapp.security.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
  public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
    return ResponseEntity.ok(authService.refresh(request));
  }
}
