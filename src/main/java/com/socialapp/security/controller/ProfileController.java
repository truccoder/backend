package com.socialapp.security.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.security.dto.ChangePasswordRequestDto;
import com.socialapp.security.dto.ProfilePictureResponseDto;
import com.socialapp.security.dto.UpdateProfileRequest;
import com.socialapp.security.dto.UserResponse;
import com.socialapp.security.service.ProfileService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/profile")
@RequiredArgsConstructor
public class ProfileController {
  private final ProfileService profileService;

  @GetMapping("/me")
  public UserResponse getProfile() {
    return profileService.getProfile(SecurityUtils.getCurrentUserId());
  }

  @PutMapping
  public UserResponse updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
    return profileService.updateProfile(SecurityUtils.getCurrentUserId(), request);
  }

  @PutMapping("/password")
  public void changePassword(@Valid @RequestBody ChangePasswordRequestDto request) {
    profileService.changePassword(SecurityUtils.getCurrentUserId(), request);
  }

  @PutMapping(value = "/picture", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ProfilePictureResponseDto changeProfilePicture(@RequestPart("file") MultipartFile file) {
    String url = profileService.changeProfilePicture(SecurityUtils.getCurrentUserId(), file);
    return new ProfilePictureResponseDto(url);
  }
}
