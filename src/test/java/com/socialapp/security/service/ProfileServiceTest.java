package com.socialapp.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.cloud.minio.MinIOConfig;
import com.socialapp.cloud.minio.MinIOService;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.StorageException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.friendships.cache.UserProfileCache;
import com.socialapp.roadmap.enums.VerificationStatus;
import com.socialapp.roadmap.repository.UserRoadmapProgressRepository;
import com.socialapp.security.dto.ChangePasswordRequestDto;
import com.socialapp.security.dto.PublicProfileResponse;
import com.socialapp.security.dto.UpdateProfileRequest;
import com.socialapp.security.dto.UserResponse;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.RefreshTokenRepository;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link ProfileService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3 BDD
 * Given/When/Then) — see {@code PostServiceTest} for the full rationale.
 */
@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

  private static final Integer USER_ID = 1;
  private static final String USERNAME = "jane-doe";
  private static final String ENCODED_PASSWORD = "{bcrypt}encoded";

  @Mock private UserRepository userRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private MinIOService minIOService;
  @Mock private MinIOConfig minIOConfig;
  @Mock private UserProfileCache userProfileCache;
  @Mock private UserRoadmapProgressRepository progressRepository;
  @Mock private RefreshTokenRepository refreshTokenRepository;

  @InjectMocks private ProfileService profileService;

  private static UserEntity user(Integer id) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setEmail("user@example.com");
    user.setPassword(ENCODED_PASSWORD);
    user.setFullName("Jane Doe");
    user.setUsername(USERNAME);
    return user;
  }

  private static MultipartFile mockFile(String filename, long size, String contentType) {
    MultipartFile file = mock(MultipartFile.class);
    when(file.isEmpty()).thenReturn(false);
    when(file.getSize()).thenReturn(size);
    // Not every test reaches the content-type check or the filename-based extension lookup —
    // both are lenient so tests that throw earlier don't trip strict-stubbing.
    lenient().when(file.getContentType()).thenReturn(contentType);
    lenient().when(file.getOriginalFilename()).thenReturn(filename);
    return file;
  }

  // =====================================================================
  // getProfile
  // =====================================================================

  @Nested
  @DisplayName("getProfile")
  class GetProfileTests {

    @Test
    @DisplayName("should return the mapped profile when the user exists")
    void shouldReturnProfile_whenUserExists() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));

      // When
      UserResponse response = profileService.getProfile(USER_ID);

      // Then
      assertThat(response.id()).isEqualTo(USER_ID);
      assertThat(response.fullName()).isEqualTo("Jane Doe");
    }

    @Test
    @DisplayName("should reject when the user does not exist")
    void shouldThrowNotFoundException_whenUserDoesNotExist() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> profileService.getProfile(USER_ID))
          .isInstanceOf(NotFoundException.class);
    }
  }

  // =====================================================================
  // updateProfile
  // =====================================================================

  @Nested
  @DisplayName("updateProfile")
  class UpdateProfileTests {

    @Test
    @DisplayName("should update the full name and evict the cached profile")
    void shouldUpdateFullNameAndEvictCache() {
      // Given
      UserEntity user = user(USER_ID);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

      // When
      UserResponse response =
          profileService.updateProfile(USER_ID, new UpdateProfileRequest("New Name"));

      // Then
      assertThat(user.getFullName()).isEqualTo("New Name");
      assertThat(response.fullName()).isEqualTo("New Name");
      verify(userRepository).save(user);
      verify(userProfileCache).evict(USER_ID);
    }

    @Test
    @DisplayName("should reject when the user does not exist")
    void shouldThrowNotFoundException_whenUserDoesNotExist() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> profileService.updateProfile(USER_ID, new UpdateProfileRequest("X")))
          .isInstanceOf(NotFoundException.class);
    }
  }

  // =====================================================================
  // changePassword
  // =====================================================================

  @Nested
  @DisplayName("changePassword")
  class ChangePasswordTests {

    @Test
    @DisplayName("should reject when the user does not exist")
    void shouldThrowNotFoundException_whenUserDoesNotExist() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(
              () ->
                  profileService.changePassword(
                      USER_ID, new ChangePasswordRequestDto("old", "new123")))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should reject when the current password is wrong")
    void shouldThrowBadCredentials_whenCurrentPasswordWrong() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(passwordEncoder.matches("wrong", ENCODED_PASSWORD)).thenReturn(false);

      // When / Then
      assertThatThrownBy(
              () ->
                  profileService.changePassword(
                      USER_ID, new ChangePasswordRequestDto("wrong", "new123")))
          .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("should reject when the new password matches the current one")
    void shouldThrowValidationException_whenNewPasswordSameAsCurrent() {
      // Given
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(passwordEncoder.matches("old123", ENCODED_PASSWORD)).thenReturn(true);
      when(passwordEncoder.matches("same123", ENCODED_PASSWORD)).thenReturn(true);

      // When / Then
      assertThatThrownBy(
              () ->
                  profileService.changePassword(
                      USER_ID, new ChangePasswordRequestDto("old123", "same123")))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("must be different");
    }

    @Test
    @DisplayName("should update the password when all checks pass")
    void shouldChangePassword_whenValid() {
      // Given
      UserEntity user = user(USER_ID);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(passwordEncoder.matches("old123", ENCODED_PASSWORD)).thenReturn(true);
      when(passwordEncoder.matches("new123", ENCODED_PASSWORD)).thenReturn(false);
      when(passwordEncoder.encode("new123")).thenReturn("{bcrypt}newEncoded");

      // When
      profileService.changePassword(USER_ID, new ChangePasswordRequestDto("old123", "new123"));

      // Then
      assertThat(user.getPassword()).isEqualTo("{bcrypt}newEncoded");
      verify(userRepository).save(user);
    }
  }

  // =====================================================================
  // changeProfilePicture
  // =====================================================================

  @Nested
  @DisplayName("changeProfilePicture")
  class ChangeProfilePictureTests {

    @Test
    @DisplayName("should reject a null file")
    void shouldThrowValidationException_whenFileIsNull() {
      // When / Then
      assertThatThrownBy(() -> profileService.changeProfilePicture(USER_ID, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("File is empty");
    }

    @Test
    @DisplayName("should reject an empty file")
    void shouldThrowValidationException_whenFileIsEmpty() {
      // Given
      MultipartFile file = mock(MultipartFile.class);
      when(file.isEmpty()).thenReturn(true);

      // When / Then
      assertThatThrownBy(() -> profileService.changeProfilePicture(USER_ID, file))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("File is empty");
    }

    @Test
    @DisplayName("should reject a file larger than 5MB")
    void shouldThrowValidationException_whenFileExceedsMaxSize() {
      // Given
      MultipartFile file = mockFile("avatar.png", 6L * 1024 * 1024, "image/png");

      // When / Then
      assertThatThrownBy(() -> profileService.changeProfilePicture(USER_ID, file))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("exceeds maximum size");
    }

    @Test
    @DisplayName("should reject when the content type is null")
    void shouldThrowValidationException_whenContentTypeIsNull() {
      // Given
      MultipartFile file = mockFile("avatar.png", 1024, null);

      // When / Then
      assertThatThrownBy(() -> profileService.changeProfilePicture(USER_ID, file))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("JPEG, PNG, or WEBP");
    }

    @Test
    @DisplayName("should reject an unsupported content type")
    void shouldThrowValidationException_whenContentTypeNotAllowed() {
      // Given
      MultipartFile file = mockFile("avatar.gif", 1024, "image/gif");

      // When / Then
      assertThatThrownBy(() -> profileService.changeProfilePicture(USER_ID, file))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("JPEG, PNG, or WEBP");
    }

    @Test
    @DisplayName("should reject when the user does not exist")
    void shouldThrowNotFoundException_whenUserDoesNotExist() {
      // Given
      MultipartFile file = mockFile("avatar.png", 1024, "image/png");
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> profileService.changeProfilePicture(USER_ID, file))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should default to a jpg extension when the filename is null")
    void shouldDefaultToJpgExtension_whenFilenameIsNull() throws Exception {
      // Given
      MultipartFile file = mockFile(null, 1024, "image/png");
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(minIOConfig.getUrl()).thenReturn("http://minio");

      // When
      String url = profileService.changeProfilePicture(USER_ID, file);

      // Then
      assertThat(url).endsWith(".jpg");
    }

    @Test
    @DisplayName("should default to a jpg extension when the filename has none")
    void shouldDefaultToJpgExtension_whenFilenameHasNoExtension() throws Exception {
      // Given
      MultipartFile file = mockFile("avatar", 1024, "image/png");
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(minIOConfig.getUrl()).thenReturn("http://minio");

      // When
      String url = profileService.changeProfilePicture(USER_ID, file);

      // Then
      assertThat(url).endsWith(".jpg");
    }

    @Test
    @DisplayName("should wrap an upload failure as a StorageException")
    void shouldThrowStorageException_whenUploadFails() throws Exception {
      // Given
      MultipartFile file = mockFile("avatar.png", 1024, "image/png");
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(minIOService.uploadFile(anyString(), anyString(), any()))
          .thenThrow(new RuntimeException("minio down"));

      // When / Then
      assertThatThrownBy(() -> profileService.changeProfilePicture(USER_ID, file))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Failed to upload profile picture");
    }

    @Test
    @DisplayName("should upload, save the public URL, and evict the cache on success")
    void shouldUploadAndReturnPublicUrl_onSuccess() throws Exception {
      // Given
      MultipartFile file = mockFile("avatar.png", 1024, "image/png");
      UserEntity user = user(USER_ID);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(minIOConfig.getUrl()).thenReturn("http://minio:9000");

      // When
      String url = profileService.changeProfilePicture(USER_ID, file);

      // Then
      assertThat(url).startsWith("http://minio:9000/profile-pictures/avatars/1/").endsWith(".png");
      assertThat(user.getProfilePictureUrl()).isEqualTo(url);
      verify(minIOService).uploadFile(anyString(), anyString(), any());
      verify(minIOService).ensurePublicReadPolicy("profile-pictures");
      verify(userRepository).save(user);
      verify(userProfileCache).evict(USER_ID);
    }
  }

  @Nested
  @DisplayName("getPublicProfile")
  class GetPublicProfileTests {

    @Test
    @DisplayName("should return identity fields only — never the email, verified flag or role")
    void shouldNotLeakPrivateFields() {
      // Given
      when(userRepository.findByUsernameIgnoreCase(USERNAME))
          .thenReturn(Optional.of(user(USER_ID)));

      // When
      PublicProfileResponse response = profileService.getPublicProfile(USERNAME);

      // Then — the record has no email/role component at all, which is the point: this is
      // enforced by the type, not by remembering to leave fields out
      assertThat(response.id()).isEqualTo(USER_ID);
      assertThat(response.fullName()).isEqualTo("Jane Doe");
      assertThat(PublicProfileResponse.class.getRecordComponents())
          .extracting(java.lang.reflect.RecordComponent::getName)
          .doesNotContain("email", "emailVerified", "role", "password");
    }

    @Test
    @DisplayName(
        "should resolve the reputation level from the score rather than leave it to the client")
    void shouldResolveLevelFromScore() {
      // Given — 120 sits inside CONTRIBUTOR (100) with PRACTITIONER (1000) next
      UserEntity subject = user(USER_ID);
      subject.setEliteScore(120);
      when(userRepository.findByUsernameIgnoreCase(USERNAME)).thenReturn(Optional.of(subject));

      // When
      PublicProfileResponse response = profileService.getPublicProfile(USERNAME);

      // Then
      assertThat(response.level()).isEqualTo(2);
      assertThat(response.levelName()).isEqualTo("Contributor");
      assertThat(response.currentLevelMin()).isEqualTo(100);
      assertThat(response.nextLevelMin()).isEqualTo(1000);
    }

    @Test
    @DisplayName("should report no next level at the top of the ladder (boundary: ELITE)")
    void shouldReportNoNextLevelAtElite() {
      // Given — BVA: exactly the ELITE threshold, where next() has nothing to return
      UserEntity subject = user(USER_ID);
      subject.setEliteScore(50_000);
      when(userRepository.findByUsernameIgnoreCase(USERNAME)).thenReturn(Optional.of(subject));

      // When
      PublicProfileResponse response = profileService.getPublicProfile(USERNAME);

      // Then
      assertThat(response.levelName()).isEqualTo("Elite");
      assertThat(response.nextLevelMin()).isNull();
    }

    @Test
    @DisplayName("should carry only the verified skills, in roadmap order")
    void shouldCarryVerifiedSkills() {
      // Given
      when(userRepository.findByUsernameIgnoreCase(USERNAME))
          .thenReturn(Optional.of(user(USER_ID)));
      when(progressRepository.findSkillNamesByUserIdAndStatus(USER_ID, VerificationStatus.VERIFIED))
          .thenReturn(java.util.List.of("Java Core", "Spring Boot"));

      // When
      PublicProfileResponse response = profileService.getPublicProfile(USERNAME);

      // Then — the status filter is the query's job; what this pins is that the profile asks for
      // VERIFIED and nothing else, so a pending or rejected claim can never reach a stranger.
      assertThat(response.verifiedSkills()).containsExactly("Java Core", "Spring Boot");
    }

    @Test
    @DisplayName("should throw NotFound when the user does not exist")
    void shouldThrowWhenMissing() {
      // Given
      when(userRepository.findByUsernameIgnoreCase(USERNAME)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> profileService.getPublicProfile(USERNAME))
          .isInstanceOf(NotFoundException.class);
    }
  }
}
