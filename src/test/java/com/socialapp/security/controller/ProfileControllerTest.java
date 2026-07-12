package com.socialapp.security.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.dto.UserResponse;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;
import com.socialapp.security.service.ProfileService;

/**
 * System/API integration tests for {@link ProfileController}, per ISTQB CTFL v4.0.1 Section
 * 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link ProfileService} is mocked.
 *
 * <p><b>Auth simulation note:</b> unlike {@code AuthController}, every endpoint here is behind
 * {@code .anyRequest().authenticated()} in {@link SecurityConfig}, and the controller reads the
 * caller via {@code SecurityUtils.getCurrentUserId()}, which only recognizes a principal of type
 * {@link UserEntity} — the type {@link JwtAuthenticationFilter} places in the
 * {@code SecurityContext} after validating a real bearer token. Spring Security Test's {@code
 * @WithMockUser} instead installs a generic {@code org.springframework.security.core.userdetails.User}
 * principal, which {@code SecurityUtils} does not recognize, so it would make every "authenticated"
 * test fail with 401 exactly like an anonymous request. Authenticated tests therefore go through
 * the real filter by sending a bearer token and stubbing {@link JwtProvider}/{@link UserRepository}
 * — {@code @WithMockUser} is reserved for the endpoint-is-protected 401 checks, matching what this
 * app's own authentication model actually recognizes.
 *
 * <p><b>Real 401/403 cases exist here</b> (unlike AuthController): a missing/invalid bearer token
 * is rejected with 401 by {@link CustomAuthenticationEntryPoint} before the controller runs
 * (TC_AUTH_28), and a banned user's token is rejected with 403 directly inside {@link
 * JwtAuthenticationFilter} (see {@code writeBannedResponse}), not via {@code GlobalExceptionHandler}.
 */
@WebMvcTest(ProfileController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class ProfileControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockBean private ProfileService profileService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private UserRepository userRepository;

  private static final String PROFILE_ME_URL = "/v1/api/profile/me";
  private static final String PROFILE_URL = "/v1/api/profile";
  private static final String PROFILE_PASSWORD_URL = "/v1/api/profile/password";
  private static final String PROFILE_PICTURE_URL = "/v1/api/profile/picture";
  private static final String VALID_TOKEN = "a-valid-jwt-token";

  private UserEntity currentUser;

  @BeforeEach
  void setUpDefaultUser() {
    currentUser = sampleUser(1, "user@example.com", false);
  }

  private static UserEntity sampleUser(Integer id, String email, boolean banned) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setEmail(email);
    user.setUsername("testuser" + id);
    user.setFullName("Test User");
    user.setRole(UserRole.USER);
    user.setEmailVerified(true);
    if (banned) {
      user.setBannedUntil(OffsetDateTime.now().plusDays(1));
    }
    return user;
  }

  /**
   * Makes {@link JwtAuthenticationFilter} accept {@link #VALID_TOKEN} as belonging to {@code
   * user}, so the controller's {@code SecurityUtils.getCurrentUserId()} call resolves to a real
   * {@link UserEntity} principal — see the class-level Javadoc for why {@code @WithMockUser}
   * cannot be used for this.
   */
  private void mockAuthenticatedAs(UserEntity user) {
    when(jwtProvider.isTokenValid(VALID_TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(VALID_TOKEN)).thenReturn(user.getEmail());
    when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
  }

  private static UserResponse sampleUserResponse(UserEntity user) {
    return new UserResponse(
        user.getId(),
        user.getEmail(),
        user.getUsername(),
        user.getFullName(),
        null,
        user.isEmailVerified(),
        user.getRole(),
        OffsetDateTime.parse("2026-01-01T00:00:00Z"));
  }

  // =====================================================================
  // GET /v1/api/profile/me
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/profile/me")
  class GetProfileTests {

    @Test
    @DisplayName("shouldReturn200AndUserResponse_whenCalledByAnAuthenticatedUser_happyPath")
    void shouldReturn200AndUserResponse_whenCalledByAnAuthenticatedUser_happyPath()
        throws Exception {
      // Given
      mockAuthenticatedAs(currentUser);
      when(profileService.getProfile(currentUser.getId()))
          .thenReturn(sampleUserResponse(currentUser));

      // When / Then — TC_AUTH_26
      mockMvc
          .perform(get(PROFILE_ME_URL).header("Authorization", "Bearer " + VALID_TOKEN))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(currentUser.getId()))
          .andExpect(jsonPath("$.email").value(currentUser.getEmail()))
          .andExpect(jsonPath("$.username").value(currentUser.getUsername()))
          .andExpect(jsonPath("$.emailVerified").value(true))
          .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then — TC_AUTH_28: no token when calling an endpoint that requires login
      mockMvc.perform(get(PROFILE_ME_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("shouldReturn401_whenBearerTokenIsInvalidOrExpired")
    void shouldReturn401_whenBearerTokenIsInvalidOrExpired() throws Exception {
      // Given
      when(jwtProvider.isTokenValid("a-garbage-token")).thenReturn(false);

      // When / Then — filter never authenticates the request, same as no token at all
      mockMvc
          .perform(get(PROFILE_ME_URL).header("Authorization", "Bearer a-garbage-token"))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("shouldReturn403_whenUserIsBanned")
    void shouldReturn403_whenUserIsBanned() throws Exception {
      // Given — TC_AUTH_30-adjacent: JwtAuthenticationFilter itself rejects a banned user's
      // otherwise-valid token, before the controller/ProfileService ever runs
      UserEntity bannedUser = sampleUser(2, "banned@example.com", true);
      mockAuthenticatedAs(bannedUser);

      // When / Then
      mockMvc
          .perform(get(PROFILE_ME_URL).header("Authorization", "Bearer " + VALID_TOKEN))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.status").value("FORBIDDEN"))
          .andExpect(jsonPath("$.message").value(containsString("banned until")));
    }
  }

  // =====================================================================
  // PUT /v1/api/profile
  // =====================================================================

  @Nested
  @DisplayName("PUT /v1/api/profile")
  class UpdateProfileTests {

    @Test
    @DisplayName("shouldReturn200AndUpdatedUser_whenFullNameIsValid_happyPath")
    void shouldReturn200AndUpdatedUser_whenFullNameIsValid_happyPath() throws Exception {
      // Given — TC_AUTH_22
      mockAuthenticatedAs(currentUser);
      UserEntity updated = sampleUser(currentUser.getId(), currentUser.getEmail(), false);
      updated.setFullName("Updated Name");
      when(profileService.updateProfile(eq(currentUser.getId()), any()))
          .thenReturn(sampleUserResponse(updated));
      String requestJson =
          """
          { "fullName": "Updated Name" }
          """;

      // When / Then
      mockMvc
          .perform(
              put(PROFILE_URL)
                  .header("Authorization", "Bearer " + VALID_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.fullName").value("Updated Name"));
    }

    @ParameterizedTest
    @DisplayName("shouldReturn422_whenFullNameIsBlank")
    @ValueSource(strings = {"", "   "})
    void shouldReturn422_whenFullNameIsBlank(String blankName) throws Exception {
      // Given
      mockAuthenticatedAs(currentUser);
      String requestJson = "{\"fullName\": \"%s\"}".formatted(blankName);

      // When / Then
      mockMvc
          .perform(
              put(PROFILE_URL)
                  .header("Authorization", "Bearer " + VALID_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // Given
      String requestJson =
          """
          { "fullName": "Someone" }
          """;

      // When / Then
      mockMvc
          .perform(put(PROFILE_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // PUT /v1/api/profile/password
  // =====================================================================

  @Nested
  @DisplayName("PUT /v1/api/profile/password")
  class ChangePasswordTests {

    @Test
    @DisplayName("shouldReturn200_whenCurrentAndNewPasswordAreValid_happyPath")
    void shouldReturn200_whenCurrentAndNewPasswordAreValid_happyPath() throws Exception {
      // Given — TC_AUTH_23
      mockAuthenticatedAs(currentUser);
      String requestJson =
          """
          { "currentPassword": "12345678a", "newPassword": "newpassword123" }
          """;

      // When / Then
      mockMvc
          .perform(
              put(PROFILE_PASSWORD_URL)
                  .header("Authorization", "Bearer " + VALID_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("shouldReturn401_whenCurrentPasswordIsIncorrect")
    void shouldReturn401_whenCurrentPasswordIsIncorrect() throws Exception {
      // Given — TC_AUTH_24: ProfileService throws BadCredentialsException("Current password is
      // incorrect"), but GlobalExceptionHandler#handle(AuthenticationException, ...) discards
      // the actual exception message and always responds with the hardcoded "Invalid
      // credentials" for every AuthenticationException subtype — confirmed empirically, so this
      // asserts the real message rather than the service's original one.
      mockAuthenticatedAs(currentUser);
      org.mockito.Mockito.doThrow(new BadCredentialsException("Current password is incorrect"))
          .when(profileService)
          .changePassword(eq(currentUser.getId()), any());
      String requestJson =
          """
          { "currentPassword": "wrong-password", "newPassword": "newpassword123" }
          """;

      // When / Then
      mockMvc
          .perform(
              put(PROFILE_PASSWORD_URL)
                  .header("Authorization", "Bearer " + VALID_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @ParameterizedTest(name = "shouldReturn422_whenPasswordFieldsInvalid: {0}")
    @DisplayName("shouldReturn422_whenPasswordFieldsAreInvalid")
    @ValueSource(strings = {"", "   "})
    void shouldReturn422_whenCurrentPasswordIsBlank(String blank) throws Exception {
      // Given
      mockAuthenticatedAs(currentUser);
      String requestJson =
          "{\"currentPassword\": \"%s\", \"newPassword\": \"newpassword123\"}".formatted(blank);

      // When / Then
      mockMvc
          .perform(
              put(PROFILE_PASSWORD_URL)
                  .header("Authorization", "Bearer " + VALID_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn422_whenNewPasswordIsTooShort")
    void shouldReturn422_whenNewPasswordIsTooShort() throws Exception {
      // Given — BVA: @Size(min = 6), 3 chars is below the boundary
      mockAuthenticatedAs(currentUser);
      String requestJson =
          """
          { "currentPassword": "12345678a", "newPassword": "123" }
          """;

      // When / Then
      mockMvc
          .perform(
              put(PROFILE_PASSWORD_URL)
                  .header("Authorization", "Bearer " + VALID_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // Given
      String requestJson =
          """
          { "currentPassword": "12345678a", "newPassword": "newpassword123" }
          """;

      // When / Then
      mockMvc
          .perform(
              put(PROFILE_PASSWORD_URL)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // PUT /v1/api/profile/picture
  // =====================================================================

  @Nested
  @DisplayName("PUT /v1/api/profile/picture")
  class ChangeProfilePictureTests {

    @Test
    @DisplayName("shouldReturn200AndNewUrl_whenFileIsValid_happyPath")
    void shouldReturn200AndNewUrl_whenFileIsValid_happyPath() throws Exception {
      // Given — TC_AUTH_25
      mockAuthenticatedAs(currentUser);
      when(profileService.changeProfilePicture(eq(currentUser.getId()), any()))
          .thenReturn("https://minio.example.com/profile-pictures/new-avatar.jpg");
      MockMultipartFile file =
          new MockMultipartFile(
              "file", "avatar.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[] {1, 2, 3, 4});

      // When / Then
      mockMvc
          .perform(multipartPut(PROFILE_PICTURE_URL, file, currentUser))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.profilePictureUrl")
                  .value("https://minio.example.com/profile-pictures/new-avatar.jpg"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // Given
      MockMultipartFile file =
          new MockMultipartFile(
              "file", "avatar.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[] {1, 2, 3, 4});

      // When / Then
      mockMvc
          .perform(multipart(PROFILE_PICTURE_URL).file(file))
          .andExpect(status().isUnauthorized());
    }

    /** {@code PUT} multipart requests need the HTTP method forced to PUT explicitly. */
    private MockHttpServletRequestBuilder multipartPut(
        String url, MockMultipartFile file, UserEntity user) {
      MockMultipartHttpServletRequestBuilder builder = multipart(url).file(file);
      builder.with(
          request -> {
            request.setMethod("PUT");
            return request;
          });
      return builder.header("Authorization", "Bearer " + VALID_TOKEN);
    }
  }
}
