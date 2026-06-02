package com.socialapp.knowledge.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.knowledge.dto.UpdateProfessionalProfileDto;
import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.service.ProfessionalProfileService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/profile/professional")
@RequiredArgsConstructor
public class ProfessionalProfileController {
  private final ProfessionalProfileService profileService;

  @GetMapping
  public UserProfessionalProfileEntity getProfile(@RequestHeader("X-User-Id") Integer userId) {
    return profileService.getProfile(userId);
  }

  @PutMapping
  public UserProfessionalProfileEntity updateProfile(
      @RequestHeader("X-User-Id") Integer userId,
      @RequestBody @Valid UpdateProfessionalProfileDto request) {
    return profileService.upsertProfile(userId, request);
  }
}
