package com.socialapp.media.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.socialapp.common.exception.StorageException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.media.service.MediaService;
import com.socialapp.moderation.service.BanDetailsService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link MediaController}, per ISTQB CTFL v4.0.1 Section 2.2.2,
 * using {@code @WebMvcTest} + {@code MockMvc}. {@link MediaService} is mocked.
 *
 * <p><b>The guest case is asserted, not assumed.</b> An upload endpoint open to anonymous callers
 * is free file hosting attributable to nobody, and the object key's only link back to a person is
 * the user id taken from the security context.
 */
@WebMvcTest(MediaController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class MediaControllerTest {

  private static final String URL = "/v1/api/media";
  private static final String TOKEN = "a-valid-jwt-token";
  private static final Integer USER_ID = 9001;

  @Autowired private MockMvc mockMvc;

  @MockBean private MediaService mediaService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private BanDetailsService banDetailsService;
  @MockBean private UserRepository userRepository;

  @BeforeEach
  void setUpCaller() {
    UserEntity user = new UserEntity();
    user.setId(USER_ID);
    user.setEmail("uploader@example.com");
    user.setUsername("uploader");
    user.setFullName("Uploader");
    user.setRole(UserRole.USER);
    user.setEmailVerified(true);

    when(jwtProvider.isTokenValid(TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(TOKEN)).thenReturn(user.getEmail());
    when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
  }

  private static MockMultipartFile part(String filename) {
    return new MockMultipartFile("files", filename, "image/png", new byte[] {1, 2, 3});
  }

  private static MockMultipartHttpServletRequestBuilder authed(
      MockMultipartHttpServletRequestBuilder builder) {
    return (MockMultipartHttpServletRequestBuilder)
        builder.header("Authorization", "Bearer " + TOKEN);
  }

  @Nested
  @DisplayName("POST /v1/api/media")
  class UploadTests {

    @Test
    @DisplayName("shouldReturn200AndTheStoredUrls_happyPath")
    void shouldReturnStoredUrls() throws Exception {
      // Given
      when(mediaService.upload(eq(USER_ID), anyList()))
          .thenReturn(List.of("http://localhost:9000/post-media/posts/9001/a.png"));

      // When / Then
      mockMvc
          .perform(authed(multipart(URL)).file(part("a.png")))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.urls[0]").value("http://localhost:9000/post-media/posts/9001/a.png"));
    }

    @Test
    @DisplayName("shouldAcceptSeveralFilesUnderOnePartName")
    void shouldAcceptSeveralFiles() throws Exception {
      // Given — a batch and a single upload are the same request shape, repeated rather than
      // numbered parts, so the composer sends one kind of request either way
      when(mediaService.upload(eq(USER_ID), anyList())).thenReturn(List.of("u1", "u2"));

      // When / Then
      mockMvc
          .perform(authed(multipart(URL)).file(part("a.png")).file(part("b.png")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.urls.length()").value(2));
    }

    @Test
    @DisplayName("shouldTakeTheUploaderFromTheSecurityContext_notTheRequest")
    void shouldTakeUploaderFromSecurityContext() throws Exception {
      // Given
      when(mediaService.upload(eq(USER_ID), anyList())).thenReturn(List.of("u1"));

      // When
      mockMvc.perform(authed(multipart(URL)).file(part("a.png"))).andExpect(status().isOk());

      // Then — a userId accepted from the request would let anyone file objects under someone
      // else's prefix, which is the only thing tying an upload to an account
      verify(mediaService).upload(eq(USER_ID), anyList());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledByAGuestWithNoAuthorizationHeader")
    void shouldRefuseGuests() throws Exception {
      // When / Then
      mockMvc.perform(multipart(URL).file(part("a.png"))).andExpect(status().isUnauthorized());

      verify(mediaService, never()).upload(any(), any());
    }

    @Test
    @DisplayName("shouldReturn400_whenThePartNameIsWrong")
    void shouldReturn400_whenPartMissing() throws Exception {
      // Given — EP: the part is named "files"; anything else is a request this handler cannot read
      MockMultipartFile wrongName =
          new MockMultipartFile("image", "a.png", "image/png", new byte[] {1});

      // When / Then
      mockMvc.perform(authed(multipart(URL)).file(wrongName)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn400_whenTheServiceRejectsTheFileType")
    void shouldReturn400_whenTypeRejected() throws Exception {
      // Given
      when(mediaService.upload(eq(USER_ID), anyList()))
          .thenThrow(new ValidationException("Only JPEG, PNG, WEBP or GIF images are allowed"));

      // When / Then
      mockMvc
          .perform(authed(multipart(URL)).file(part("a.pdf")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("Only JPEG, PNG, WEBP or GIF images are allowed"));
    }

    @Test
    @DisplayName("shouldReturn503_whenTheObjectStoreIsUnreachable")
    void shouldReturn503_whenStorageFails() throws Exception {
      // Given
      when(mediaService.upload(eq(USER_ID), anyList()))
          .thenThrow(new StorageException("Failed to upload image", new RuntimeException()));

      // When / Then — a retryable 503, since the caller did nothing wrong
      mockMvc
          .perform(authed(multipart(URL)).file(part("a.png")))
          .andExpect(status().isServiceUnavailable());
    }
  }
}
