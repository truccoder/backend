package com.socialapp.security.service;

import java.util.Set;
import java.util.UUID;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.cloud.minio.MinIOConfig;
import com.socialapp.cloud.minio.MinIOService;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.StorageException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.common.utils.FileExtensions;
import com.socialapp.friendships.cache.UserProfileCache;
import com.socialapp.security.dto.ChangePasswordRequestDto;
import com.socialapp.security.dto.UpdateProfileRequest;
import com.socialapp.security.dto.UserResponse;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

  private static final String PROFILE_PICTURES_BUCKET = "profile-pictures";
  private static final long MAX_PROFILE_PICTURE_SIZE = 5L * 1024 * 1024;
  private static final Set<String> ALLOWED_PROFILE_PICTURE_TYPES =
      Set.of("image/jpeg", "image/png", "image/webp");

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final MinIOService minIOService;
  private final MinIOConfig minIOConfig;
  private final UserProfileCache userProfileCache;

  public UserResponse getProfile(Integer userId) {
    return toResponse(requireUser(userId));
  }

  @Transactional
  public UserResponse updateProfile(Integer userId, UpdateProfileRequest request) {
    UserEntity user = requireUser(userId);
    user.setFullName(request.fullName());
    userRepository.save(user);
    userProfileCache.evict(userId);
    return toResponse(user);
  }

  @Transactional
  public void changePassword(Integer userId, ChangePasswordRequestDto request) {
    UserEntity user = requireUser(userId);

    if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
      throw new BadCredentialsException("Current password is incorrect");
    }

    if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
      throw new ValidationException("New password must be different from the current password");
    }

    user.setPassword(passwordEncoder.encode(request.newPassword()));
    userRepository.save(user);
  }

  @Transactional
  public String changeProfilePicture(Integer userId, MultipartFile file) {
    validateProfilePicture(file);
    UserEntity user = requireUser(userId);

    String objectKey =
        "avatars/"
            + userId
            + "/"
            + UUID.randomUUID()
            + "."
            + FileExtensions.getExtension(file.getOriginalFilename(), "jpg");

    try {
      minIOService.uploadFile(PROFILE_PICTURES_BUCKET, objectKey, file);
      minIOService.ensurePublicReadPolicy(PROFILE_PICTURES_BUCKET);
    } catch (Exception e) {
      throw new StorageException("Failed to upload profile picture", e);
    }

    String publicUrl = minIOConfig.getUrl() + "/" + PROFILE_PICTURES_BUCKET + "/" + objectKey;
    user.setProfilePictureUrl(publicUrl);
    userRepository.save(user);
    userProfileCache.evict(userId);

    return publicUrl;
  }

  private UserEntity requireUser(Integer userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new NotFoundException("User not found: " + userId));
  }

  private UserResponse toResponse(UserEntity user) {
    return new UserResponse(
        user.getId(),
        user.getEmail(),
        user.getUsername(),
        user.getFullName(),
        user.getProfilePictureUrl(),
        user.isEmailVerified(),
        user.getRole(),
        user.getCreatedAt());
  }

  private void validateProfilePicture(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new ValidationException("File is empty");
    }
    if (file.getSize() > MAX_PROFILE_PICTURE_SIZE) {
      throw new ValidationException("File exceeds maximum size of 5MB");
    }
    String contentType = file.getContentType();
    if (contentType == null || !ALLOWED_PROFILE_PICTURE_TYPES.contains(contentType.toLowerCase())) {
      throw new ValidationException("Only JPEG, PNG, or WEBP images are allowed");
    }
  }
}
