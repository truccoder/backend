package com.socialapp.security.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.socialapp.common.exception.ValidationException;
import com.socialapp.security.dto.RegisterRequestDto;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  public void register(RegisterRequestDto request) {
    if (userRepository.existsByEmail(request.email())) {
      throw new ValidationException("Email already exists");
    }

    UserEntity user = new UserEntity();
    user.setEmail(request.email());
    user.setPassword(passwordEncoder.encode(request.password()));
    user.setFullName(request.fullname());
    user.setProfilePictureUrl(request.profilePictureUrl());

    userRepository.save(user);
  }
}
