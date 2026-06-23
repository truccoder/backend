package com.socialapp.knowledge.controller;

import org.springframework.web.bind.annotation.*;

import com.socialapp.knowledge.dto.UpdateProfessionalProfileDto;
import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.service.ProfessionalProfileService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/profile/professional")
@RequiredArgsConstructor
public class ProfessionalProfileController {
  private final ProfessionalProfileService profileService;

  @GetMapping
  public UserProfessionalProfileEntity getProfile() {
    return profileService.getProfile(SecurityUtils.getCurrentUserId());
  }

  @PutMapping
  public UserProfessionalProfileEntity updateProfile(
      @RequestBody @Valid UpdateProfessionalProfileDto request) {
    return profileService.upsertProfile(SecurityUtils.getCurrentUserId(), request);
  }
}
