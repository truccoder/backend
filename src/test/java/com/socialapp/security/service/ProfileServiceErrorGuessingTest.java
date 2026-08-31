package com.socialapp.security.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.cloud.minio.MinIOConfig;
import com.socialapp.cloud.minio.MinIOService;
import com.socialapp.common.exception.StorageException;
import com.socialapp.friendships.cache.UserProfileCache;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import io.minio.errors.MinioException;
import io.minio.errors.ServerException;

/**
 * Stage 5 (Experience-based Testing) — Error Guessing technique per ISTQB CTFL v4.0.1 Section
 * 4.5.3, applied to {@link ProfileService#changeProfilePicture}. Mirrors the scenarios already
 * covered for {@code BookStorageService} (see {@code BookStorageServiceErrorGuessingTest}), since
 * both services wrap the same {@link MinIOService} dependency and, until now, both wrapped
 * failures in a raw {@link RuntimeException} instead of a typed {@link StorageException}.
 *
 * <p>{@link MinIOService} and {@link UserRepository} are mocked — no real MinIO server or
 * database is contacted.
 */
@ExtendWith(MockitoExtension.class)
class ProfileServiceErrorGuessingTest {

  private static final Integer USER_ID = 1;

  @Mock private UserRepository userRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private MinIOService minIOService;
  @Mock private MinIOConfig minIOConfig;
  @Mock private UserProfileCache userProfileCache;

  @InjectMocks private ProfileService profileService;

  private static UserEntity user(Integer id) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setEmail("user@example.com");
    return user;
  }

  private static MultipartFile mockFile() {
    MultipartFile file = mock(MultipartFile.class);
    when(file.isEmpty()).thenReturn(false);
    when(file.getSize()).thenReturn(1024L);
    lenient().when(file.getContentType()).thenReturn("image/png");
    lenient().when(file.getOriginalFilename()).thenReturn("avatar.png");
    return file;
  }

  // =====================================================================
  // Third-party (MinIO) outages during upload
  // =====================================================================

  @Nested
  @DisplayName("MinIO client outages during upload")
  class ThirdPartyOutageTests {

    @Test
    @DisplayName("shouldThrowStorageException_whenMinioClientTimesOut")
    void shouldThrowStorageException_whenMinioClientTimesOut() throws Exception {
      // Given — the MinIO server accepted the connection but never responded in time
      MultipartFile file = mockFile();
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(minIOService.uploadFile(anyString(), anyString(), any(), anyString()))
          .thenThrow(
              new StorageException(
                  "Could not store the object", new SocketTimeoutException("Read timed out")));

      // When / Then
      assertThatThrownBy(() -> profileService.changeProfilePicture(USER_ID, file))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Could not store")
          .hasRootCauseInstanceOf(SocketTimeoutException.class);
    }

    @Test
    @DisplayName("shouldThrowStorageException_whenNetworkConnectionIsRefused")
    void shouldThrowStorageException_whenNetworkConnectionIsRefused() throws Exception {
      // Given — MinIO host is unreachable (container down, DNS failure, firewall, etc.)
      MultipartFile file = mockFile();
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(minIOService.uploadFile(anyString(), anyString(), any(), anyString()))
          .thenThrow(
              new StorageException(
                  "Could not store the object", new ConnectException("Connection refused")));

      // When / Then
      assertThatThrownBy(() -> profileService.changeProfilePicture(USER_ID, file))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Could not store")
          .hasRootCauseInstanceOf(ConnectException.class);
    }

    @Test
    @DisplayName("shouldThrowStorageException_whenMinioServerReturns500")
    void shouldThrowStorageException_whenMinioServerReturns500() throws Exception {
      // Given — MinIO responded, but with a genuine server-side failure
      MultipartFile file = mockFile();
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(minIOService.uploadFile(anyString(), anyString(), any(), anyString()))
          .thenThrow(
              new StorageException(
                  "Could not store the object",
                  new ServerException("Internal error", 500, "trace-id-abc123")));

      // When / Then
      assertThatThrownBy(() -> profileService.changeProfilePicture(USER_ID, file))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Could not store")
          .hasRootCauseInstanceOf(ServerException.class);
    }

    @Test
    @DisplayName("shouldThrowStorageException_whenMinioThrowsGenericMinioException")
    void shouldThrowStorageException_whenMinioThrowsGenericMinioException() throws Exception {
      // Given — any other checked failure from the MinIO SDK's exception hierarchy
      MultipartFile file = mockFile();
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(minIOService.uploadFile(anyString(), anyString(), any(), anyString()))
          .thenThrow(
              new StorageException(
                  "Could not store the object",
                  new MinioException("Unexpected internal SDK failure")));

      // When / Then
      assertThatThrownBy(() -> profileService.changeProfilePicture(USER_ID, file))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Could not store")
          .hasRootCauseInstanceOf(MinioException.class);
    }

    @Test
    @DisplayName("shouldNotTouchTheBucketPolicy_becauseThatMovedToStartup")
    void shouldNotTouchTheBucketPolicy() throws Exception {
      // Given — this used to assert that a failing ensurePublicReadPolicy aborted the upload. That
      // call is no longer on this path at all: the policy is applied once by MinIOBucketInitializer
      // when the application starts, rather than on every avatar change inside the transaction.
      // What is left worth asserting is the negative — that changing a picture no longer performs a
      // bucket-administration round trip.
      MultipartFile file = mockFile();
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(minIOConfig.getUrl()).thenReturn("http://minio:9000");

      // When
      profileService.changeProfilePicture(USER_ID, file);

      // Then
      verify(minIOService, never()).ensurePublicReadPolicy(anyString());
    }

    @Test
    @DisplayName("shouldPreserveOriginalCauseChain_whenMinioClientFails")
    void shouldPreserveOriginalCauseChain_whenMinioClientFails() throws Exception {
      // Given — diagnosability matters: the original low-level exception must not be swallowed
      MultipartFile file = mockFile();
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      SocketTimeoutException original = new SocketTimeoutException("Read timed out");
      when(minIOService.uploadFile(anyString(), anyString(), any(), anyString()))
          .thenThrow(new StorageException("Could not store the object", original));

      // When / Then
      assertThatThrownBy(() -> profileService.changeProfilePicture(USER_ID, file))
          .isInstanceOf(StorageException.class)
          .rootCause()
          .isSameAs(original);
    }

    @Test
    @DisplayName("shouldNotThrowNullPointerException_whenMinioExceptionHasNullMessage")
    void shouldNotThrowNullPointerException_whenMinioExceptionHasNullMessage() throws Exception {
      // Given — a third-party exception with no message at all is a realistic worst case
      MultipartFile file = mockFile();
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
      when(minIOService.uploadFile(anyString(), anyString(), any(), anyString()))
          .thenThrow(
              new StorageException("Could not store the object", new IOException((String) null)));

      // When / Then — the service's own wrapping message must still be intact, no NPE while
      // building the StorageException itself
      assertThatThrownBy(() -> profileService.changeProfilePicture(USER_ID, file))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("Could not store");
    }
  }
}
