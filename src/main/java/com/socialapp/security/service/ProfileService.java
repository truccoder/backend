package com.socialapp.security.service;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.cloud.minio.MinIOConfig;
import com.socialapp.cloud.minio.MinIOService;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.friendships.cache.UserProfileCache;
import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.repository.UserProfessionalProfileRepository;
import com.socialapp.reputation.RepLevel;
import com.socialapp.roadmap.enums.VerificationStatus;
import com.socialapp.roadmap.repository.UserRoadmapProgressRepository;
import com.socialapp.security.dto.ChangePasswordRequestDto;
import com.socialapp.security.dto.PublicProfileResponse;
import com.socialapp.security.dto.PublicUserResponse;
import com.socialapp.security.dto.UpdateProfileRequest;
import com.socialapp.security.dto.UserResponse;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.RefreshTokenRepository;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

  private static final String PROFILE_PICTURES_BUCKET = "profile-pictures";
  private static final long MAX_PROFILE_PICTURE_SIZE = 5L * 1024 * 1024;

  /**
   * Content types accepted, and the extension each is stored under.
   *
   * <p>Same table, and the same reasoning, as {@code MediaService.ALLOWED_TYPES} — whose javadoc
   * named this class as the one still getting it wrong. Two things matter here and both used to be
   * taken from the caller:
   *
   * <ul>
   *   <li><b>The extension comes from this map, not from {@code getOriginalFilename()}.</b> The
   *       bucket is served public-read, so the name an object is stored under is what a browser
   *       sees when someone opens the URL directly. Taking the suffix from the upload let a caller
   *       store {@code avatars/7/<uuid>.html} on the project's own storage host.
   *   <li><b>The stored content type comes from this map too</b> — see {@code storedContentType}
   *       below — rather than being echoed back from whatever the multipart part declared.
   * </ul>
   *
   * <p>GIF is deliberately absent, unlike the post-image list: an animated avatar is a distraction.
   */
  private static final Map<String, String> ALLOWED_PROFILE_PICTURE_TYPES =
      Map.of(
          "image/jpeg", "jpg",
          "image/png", "png",
          "image/webp", "webp");

  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final MinIOService minIOService;
  private final MinIOConfig minIOConfig;
  private final UserProfileCache userProfileCache;
  private final UserRoadmapProgressRepository progressRepository;
  private final UserProfessionalProfileRepository professionalProfileRepository;

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

    // Absent for anyone who never filled the form in, which is most accounts — so this is an
    // Optional to read four fields out of, not a row to require.
    Optional<UserProfessionalProfileEntity> professional =
        professionalProfileRepository.findById(user.getId());

    return new PublicProfileResponse(
        user.getId(),
        user.getUsername(),
        user.getFullName(),
        user.getProfilePictureUrl(),
        user.getCoverImageUrl(),
        user.getEliteScore(),
        user.getCreatedAt(),
        level.getLevel(),
        level.getDisplayName(),
        level.getMin(),
        next != null ? next.getMin() : null,
        // Four fields, and deliberately not the whole professional record — see
        // PublicProfileResponse#jobTitle for what is left behind and why.
        professional.map(UserProfessionalProfileEntity::getJobTitle).orElse(null),
        professional.map(UserProfessionalProfileEntity::getPrimaryRole).orElse(null),
        professional.map(UserProfessionalProfileEntity::getSeniorityLevel).orElse(null),
        professional.map(UserProfessionalProfileEntity::getYearsOfExperience).orElse(null),
        progressRepository.findSkillNamesByUserIdAndStatus(
            user.getId(), VerificationStatus.VERIFIED));
  }

  @Transactional
  public UserResponse updateProfile(Integer userId, UpdateProfileRequest request) {
    UserEntity user = requireUser(userId);
    user.setFullName(request.fullName());

    // Null leaves the cover alone; empty removes it. See UpdateProfileRequest#coverImageUrl —
    // every caller that existed before this field was added sends fullName and nothing else, so
    // copying a null through here would wipe the cover of anyone who edited their name afterwards.
    if (Objects.nonNull(request.coverImageUrl())) {
      user.setCoverImageUrl(request.coverImageUrl().isBlank() ? null : request.coverImageUrl());
    }

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

    // Same reasoning as AuthService#resetPassword: changing a password has to end the sessions
    // that were opened with the old one, or a stolen refresh token outlives the change.
    refreshTokenRepository.deleteByUserId(userId);
  }

  @Transactional
  public String changeProfilePicture(Integer userId, MultipartFile file) {
    validateProfilePicture(file);
    UserEntity user = requireUser(userId);

    String contentType = normalisedContentType(file);
    String objectKey =
        "avatars/"
            + userId
            + "/"
            + UUID.randomUUID()
            + "."
            + ALLOWED_PROFILE_PICTURE_TYPES.get(contentType);

    // Policy set once at startup by MinIOBucketInitializer; MinIOService raises StorageException
    // on its own, so there is no catch here to widen.
    minIOService.uploadFile(PROFILE_PICTURES_BUCKET, objectKey, file, contentType);

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
        user.getCoverImageUrl(),
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
    if (!ALLOWED_PROFILE_PICTURE_TYPES.containsKey(normalisedContentType(file))) {
      throw new ValidationException("Only JPEG, PNG, or WEBP images are allowed");
    }
  }

  /**
   * The declared content type, lower-cased and stripped of any {@code ;charset=…} parameter.
   *
   * <p>Declared, not detected: this is what the client said and a client can say anything. It is
   * not load-bearing on its own — what decides how the stored object is served is the extension and
   * the stored content type, and both now come from {@link #ALLOWED_PROFILE_PICTURE_TYPES} rather
   * than from the request. The worst a lying caller achieves is a PNG-named object that is not a
   * PNG, which a browser will decline to render.
   *
   * <p>{@code Locale.ROOT} because the default locale is not pinned anywhere and Turkish lower-cases
   * {@code I} to a dotless {@code ı}, which would stop {@code IMAGE/PNG} matching.
   */
  private String normalisedContentType(MultipartFile file) {
    String contentType = file.getContentType();
    if (contentType == null) {
      return "";
    }
    int parameterStart = contentType.indexOf(';');
    return (parameterStart < 0 ? contentType : contentType.substring(0, parameterStart))
        .trim()
        .toLowerCase(Locale.ROOT);
  }
}
