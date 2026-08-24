package com.socialapp.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.cloud.minio.MinIOConfig;
import com.socialapp.cloud.minio.MinIOService;
import com.socialapp.common.exception.StorageException;
import com.socialapp.common.exception.ValidationException;

/**
 * Component tests for {@link MediaService}, per ISTQB CTFL v4.0.1 Section 2.2.2. MinIO is mocked —
 * what is under test is the validation and the object key, not the object store.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaServiceTest {

  private static final Integer USER_ID = 9001;
  private static final String MINIO_URL = "http://localhost:9000";

  @Mock private MinIOService minIOService;
  @Mock private MinIOConfig minIOConfig;

  @InjectMocks private MediaService mediaService;

  @BeforeEach
  void setUp() {
    when(minIOConfig.getUrl()).thenReturn(MINIO_URL);
  }

  private static MultipartFile image(String filename, String contentType) {
    return new MockMultipartFile("files", filename, contentType, new byte[] {1, 2, 3});
  }

  @Nested
  @DisplayName("upload")
  class UploadTests {

    @Test
    @DisplayName("shouldReturnOneUrlPerFile_inTheOrderTheyWereSent_happyPath")
    void shouldReturnOneUrlPerFileInOrder() throws Exception {
      // Given
      List<MultipartFile> files =
          List.of(image("a.jpg", "image/jpeg"), image("b.png", "image/png"));

      // When
      List<String> urls = mediaService.upload(USER_ID, files);

      // Then — the caller pastes these into images[] positionally, so order is part of the
      // contract rather than an accident of iteration
      assertThat(urls).hasSize(2);
      assertThat(urls.get(0)).startsWith(MINIO_URL + "/post-media/posts/" + USER_ID + "/");
      assertThat(urls.get(0)).endsWith(".jpg");
      assertThat(urls.get(1)).endsWith(".png");
      verify(minIOService, org.mockito.Mockito.times(2))
          .uploadFile(eq("post-media"), anyString(), any());
    }

    @Test
    @DisplayName("shouldTakeTheExtensionFromTheContentType_notFromTheFilename")
    void shouldTakeExtensionFromContentType() throws Exception {
      // Given — a filename chosen to be dangerous if it survived: the bucket is public-read, so
      // an object stored as .html is a page this domain serves on the uploader's behalf
      MultipartFile disguised = image("payload.html", "image/png");

      // When
      List<String> urls = mediaService.upload(USER_ID, List.of(disguised));

      // Then
      assertThat(urls.get(0)).endsWith(".png");
      assertThat(urls.get(0)).doesNotContain(".html");
    }

    @Test
    @DisplayName("shouldNamespaceTheObjectKeyByUploader")
    void shouldNamespaceObjectKeyByUploader() throws Exception {
      // Given / When
      mediaService.upload(USER_ID, List.of(image("a.webp", "image/webp")));

      // Then — an anonymous pile of UUIDs is untraceable; the uploader is in the key so a stored
      // object can be tied back to an account
      ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
      verify(minIOService).uploadFile(eq("post-media"), key.capture(), any());
      assertThat(key.getValue()).startsWith("posts/" + USER_ID + "/");
    }

    @Test
    @DisplayName("shouldMakeTheBucketPublicallyReadable_soAnImgTagCanLoadIt")
    void shouldEnsurePublicReadPolicy() throws Exception {
      // Given / When
      mediaService.upload(USER_ID, List.of(image("a.gif", "image/gif")));

      // Then — the URL is handed to a browser that presents no credentials
      verify(minIOService).ensurePublicReadPolicy("post-media");
    }

    @Test
    @DisplayName("shouldAcceptAContentTypeCarryingACharsetParameter")
    void shouldAcceptContentTypeWithParameters() throws Exception {
      // Given — some clients send "image/jpeg; charset=UTF-8", which is legal and meaningless
      // here; a naive equals() against the allow-list would reject it
      List<String> urls = mediaService.upload(USER_ID, List.of(image("a.jpg", "image/jpeg; q=1")));

      // Then
      assertThat(urls).hasSize(1);
    }

    @Test
    @DisplayName("shouldRejectANonImageContentType_validationEP")
    void shouldRejectNonImage() {
      // Given / When / Then — EP: the allow-list is the whole of the type check
      assertThatThrownBy(
              () -> mediaService.upload(USER_ID, List.of(image("x.pdf", "application/pdf"))))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("JPEG");
    }

    @Test
    @DisplayName("shouldRejectAFileWithNoContentTypeAtAll")
    void shouldRejectMissingContentType() {
      // Given — a multipart part is free to omit it, and an absent type must not read as allowed
      MultipartFile untyped = new MockMultipartFile("files", "a.jpg", null, new byte[] {1});

      // When / Then
      assertThatThrownBy(() -> mediaService.upload(USER_ID, List.of(untyped)))
          .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("shouldRejectAnEmptyFile")
    void shouldRejectEmptyFile() {
      // Given
      MultipartFile empty = new MockMultipartFile("files", "a.jpg", "image/jpeg", new byte[0]);

      // When / Then
      assertThatThrownBy(() -> mediaService.upload(USER_ID, List.of(empty)))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("shouldRejectAnEmptyRequest")
    void shouldRejectNoFiles() {
      // When / Then
      assertThatThrownBy(() -> mediaService.upload(USER_ID, List.of()))
          .isInstanceOf(ValidationException.class);
      assertThatThrownBy(() -> mediaService.upload(USER_ID, null))
          .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("shouldAcceptExactlyTenFilesAndRejectEleven_boundary")
    void shouldEnforceTheFileCount() throws Exception {
      // Given — BVA on MAX_FILES: 10 passes, 11 does not
      List<MultipartFile> ten =
          IntStream.range(0, 10).mapToObj(i -> image(i + ".jpg", "image/jpeg")).toList();
      List<MultipartFile> eleven =
          IntStream.range(0, 11).mapToObj(i -> image(i + ".jpg", "image/jpeg")).toList();

      // When / Then
      assertThat(mediaService.upload(USER_ID, ten)).hasSize(10);
      assertThatThrownBy(() -> mediaService.upload(USER_ID, eleven))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("10");
    }

    @Test
    @DisplayName("shouldValidateEveryFileBeforeStoringAnyOfThem")
    void shouldValidateEverythingUpFront() throws Exception {
      // Given — a good file first, a bad one second
      List<MultipartFile> mixed =
          List.of(image("a.jpg", "image/jpeg"), image("b.exe", "application/octet-stream"));

      // When / Then
      assertThatThrownBy(() -> mediaService.upload(USER_ID, mixed))
          .isInstanceOf(ValidationException.class);

      // Then — nothing was written. Validating inside the upload loop would have left the first
      // file in the bucket, referenced by nothing, after a request that returned 400.
      verify(minIOService, never()).uploadFile(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("shouldSurfaceAStorageFailureAsStorageException_not500")
    void shouldWrapStorageFailures() throws Exception {
      // Given
      when(minIOService.uploadFile(anyString(), anyString(), any()))
          .thenThrow(new RuntimeException("minio is down"));

      // When / Then — StorageException maps to a retryable 503; the caller did nothing wrong
      assertThatThrownBy(() -> mediaService.upload(USER_ID, List.of(image("a.jpg", "image/jpeg"))))
          .isInstanceOf(StorageException.class);
    }
  }
}
