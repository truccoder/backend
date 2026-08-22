package com.socialapp.security.service;

import java.util.Objects;
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
import com.socialapp.reputation.RepLevel;
import com.socialapp.roadmap.enums.VerificationStatus;
import com.socialapp.roadmap.repository.UserRoadmapProgressRepository;
import com.socialapp.security.dto.ChangePasswordRequestDto;
import com.socialapp.security.dto.PublicProfileResponse;
import com.socialapp.security.dto.PublicUserResponse;
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
  private final UserRoadmapProgressRepository progressRepository;

  public UserResponse getProfile(Integer userId) {
    return toResponse(requireUser(userId));
  }

  /**
   * The same user, as someone else is allowed to see them — looked up by handle.
   *
   * <p>Split from {@link #getProfile} by return type rather than by a boolean flag: the caller of
   * that method is always the subject, so it may carry {@code email}/{@code role}, and this one
   * must never. Keeping the distinction in the type means a future edit cannot accidentally widen
   * the public shape — see {@link PublicUserResponse}.
   *
   * <p>Returns {@link PublicProfileResponse}, not the shared {@link PublicUserResponse}: the
   * profile page needs the reputation level and the verified-skill strip, and those cost a second
   * query each. Putting them on the shared record would have charged every reactor list and every
   * feed author for them — see {@link PublicProfileResponse} for the full reasoning.
   *
   * <p>Takes a <b>username</b>, not an id, because the public profile URL is {@code /u/{username}}
   * — ids are sequential, and a profile routed by id lets anyone enumerate the whole user table by
   * counting upwards. The response still carries {@code id}, deliberately: it is the one lookup
   * that turns a handle into the id every other per-user endpoint ({@code /users/{userId}/posts},
   * {@code /reputation}, {@code /github/stats}) already takes.
   */
  @Transactional(readOnly = true)
  public PublicProfileResponse getPublicProfile(String username) {
    UserEntity user =
        userRepository
            .findByUsernameIgnoreCase(username)
            .orElseThrow(() -> new NotFoundException("User not found: " + username));

    // Resolved here rather than left to the client. The thresholds live in RepLevel and are
    // mirrored by the design system's RepScore component; a client deriving the label from the raw
    // score would be a second copy of the table that silently disagrees the day a threshold moves.
    int score = Objects.requireNonNullElse(user.getEliteScore(), 0);
    RepLevel level = RepLevel.forScore(score);
    RepLevel next = level.next();

    return new PublicProfileResponse(
        user.getId(),
        user.getUsername(),
        user.getFullName(),
        user.getProfilePictureUrl(),
        user.getEliteScore(),
        user.getCreatedAt(),
        level.getLevel(),
        level.getDisplayName(),
        level.getMin(),
        next != null ? next.getMin() : null,
        progressRepository.findSkillNamesByUserIdAndStatus(
            user.getId(), VerificationStatus.VERIFIED));
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
