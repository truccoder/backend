package com.socialapp.security.service;

import com.socialapp.common.exception.ValidationException;
import com.socialapp.security.dto.AuthResponse;
import com.socialapp.security.dto.LoginRequestDto;
import com.socialapp.security.dto.RefreshTokenRequest;
import com.socialapp.security.dto.RegisterRequestDto;
import com.socialapp.security.entity.RefreshToken;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.RefreshTokenRepository;
import com.socialapp.security.repository.UserRepository;
import com.socialapp.security.util.EmailNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

  private static final String INVALID_CREDENTIALS = "Invalid credentials";

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final TokenService tokenService;
  private final RefreshTokenRepository refreshTokenRepository;

  @Transactional
  public void register(RegisterRequestDto request) {
    String email = EmailNormalizer.normalize(request.email());
    if (userRepository.existsByEmailIgnoreCase(email)) {
      throw new ValidationException("Email already exists");
    }

    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPassword(passwordEncoder.encode(request.password()));
    user.setFullName(request.fullname());
    user.setProfilePictureUrl(request.profilePictureUrl());

    userRepository.save(user);
  }

  @Transactional
  public void login(LoginRequestDto request) {
    UserEntity user = authenticate(request.email(), request.password());
    tokenService.issueTokens(user);
  }

  @Transactional
  public AuthResponse refresh(RefreshTokenRequest request) {
    RefreshToken stored =
        refreshTokenRepository
            .findById(request.refreshToken())
            .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));

    if (tokenService.isRefreshTokenExpired(stored)) {
      refreshTokenRepository.delete(stored);
      throw new BadCredentialsException(INVALID_CREDENTIALS);
    }

    UserEntity user =
        userRepository
            .findById(stored.getUserId())
            .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));

    refreshTokenRepository.delete(stored);
    return tokenService.issueTokens(user);
  }

  private UserEntity authenticate(String email, String password) {
    UserEntity user =
        userRepository
            .findByEmailIgnoreCase(EmailNormalizer.normalize(email))
            .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));

    if (!passwordEncoder.matches(password, user.getPassword())) {
      throw new BadCredentialsException(INVALID_CREDENTIALS);
    }

    return user;
  }
}
