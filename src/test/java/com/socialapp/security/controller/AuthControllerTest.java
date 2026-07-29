package com.socialapp.security.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.dto.AuthResponseDto;
import com.socialapp.security.exception.AccountBannedException;
import com.socialapp.security.repository.UserRepository;
import com.socialapp.security.service.AuthService;
import com.socialapp.security.service.OAuthAuthService;

/**
 * System/API integration tests for {@link AuthController}, per ISTQB CTFL v4.0.1 Section 2.2.2
 * (system integration testing) using {@code @WebMvcTest} + {@code MockMvc}. {@link AuthService}
 * is mocked so only the HTTP layer (routing, Bean Validation, JSON (de)serialization, and the
 * real {@link SecurityConfig} filter chain) is under test.
 *
 * <p><b>Security note:</b> {@code /v1/api/auth/**} is configured as {@code permitAll()} in {@link
 * SecurityConfig} — every endpoint in this controller is intentionally public (registration,
 * login, and password-recovery flows cannot require a pre-existing session). There is therefore
 * no 401/403 case to assert for missing tokens or wrong roles on these specific endpoints; instead
 * {@link SecurityBehaviorTests} proves the opposite property that the task description assumed
 * did not hold — that requests reach the controller with no {@code Authorization} header at all,
 * and are not accidentally blocked by an authenticated caller either.
 *
 * <p><b>Validation status code note:</b> {@code @Valid} failures on {@code @RequestBody}/{@code
 * @RequestPart} are handled by {@code GlobalExceptionHandler#handle(MethodArgumentNotValidException,
 * HttpServletRequest)}, which returns <b>422 Unprocessable Entity</b>, not 400 Bad Request. Tests
 * below assert the real 422 behavior rather than the 400 assumed by the task description.
 */
@WebMvcTest(AuthController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class AuthControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockBean private AuthService authService;
  @MockBean private OAuthAuthService oAuthAuthService;

  // JwtAuthenticationFilter's own dependencies — mocked so the real filter chain in
  // SecurityConfig can be wired up without needing a live JWT signing key or database.
  @MockBean private JwtProvider jwtProvider;
  @MockBean private UserRepository userRepository;

  private static final String REGISTER_URL = "/v1/api/auth/register";
  private static final String LOGIN_URL = "/v1/api/auth/login";
  private static final String REFRESH_URL = "/v1/api/auth/refresh";
  private static final String FORGOT_PASSWORD_URL = "/v1/api/auth/forgot-password";
  private static final String RESET_PASSWORD_URL = "/v1/api/auth/reset-password";
  private static final String VERIFY_EMAIL_URL = "/v1/api/auth/verify-email";
  private static final String MAGIC_LINK_URL = "/v1/api/auth/magic-link";
  private static final String MAGIC_LINK_LOGIN_URL = "/v1/api/auth/magic-link/login";
  private static final String LOGOUT_URL = "/v1/api/auth/logout";

  private static AuthResponseDto sampleAuthResponse() {
    return new AuthResponseDto(
        "access-token-abc", "refresh-token-xyz", "Bearer", 3600L, false, false);
  }

  // =====================================================================
  // Security behavior — /v1/api/auth/** is permitAll(), by design
  // =====================================================================

  @Nested
  @DisplayName("Security behavior (permitAll)")
  class SecurityBehaviorTests {

    @Test
    @DisplayName("shouldReturn200_whenLoginCalledWithNoAuthorizationHeader")
    void shouldReturn200_whenLoginCalledWithNoAuthorizationHeader() throws Exception {
      // Given
      when(authService.login(any())).thenReturn(sampleAuthResponse());
      String requestJson =
          """
          {
              "email": "admin1@socialapp.com",
              "password": "12345678a"
          }
          """;

      // When / Then — no Authorization header at all, request still reaches the controller
      mockMvc
          .perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "someone@example.com", roles = "USER")
    @DisplayName("shouldReturn200_whenLoginCalledByAnAlreadyAuthenticatedCaller")
    void shouldReturn200_whenLoginCalledByAnAlreadyAuthenticatedCaller() throws Exception {
      // Given
      when(authService.login(any())).thenReturn(sampleAuthResponse());
      String requestJson =
          """
          {
              "email": "admin1@socialapp.com",
              "password": "12345678a"
          }
          """;

      // When / Then — permitAll() does not block a caller who happens to already be
      // authenticated either, regardless of role
      mockMvc
          .perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isOk());
    }
  }

  // =====================================================================
  // Exception mapping — verifies that exceptions thrown by the (mocked) AuthService are
  // translated by the real GlobalExceptionHandler into the correct HTTP status and body,
  // instead of only ever exercising the Bean Validation path.
  // =====================================================================

  @Nested
  @DisplayName("Exception mapping (Service -> GlobalExceptionHandler)")
  class ExceptionMappingTests {

    @Test
    @DisplayName("shouldReturn401_whenLoginThrowsBadCredentialsException")
    void shouldReturn401_whenLoginThrowsBadCredentialsException() throws Exception {
      // Given
      when(authService.login(any())).thenThrow(new BadCredentialsException("Invalid credentials"));
      String requestJson =
          """
          { "email": "admin1@socialapp.com", "password": "wrong-password" }
          """;

      // When / Then
      mockMvc
          .perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value(401))
          .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @Test
    @DisplayName("shouldReturn403_whenLoginThrowsAccountBannedException")
    void shouldReturn403_whenLoginThrowsAccountBannedException() throws Exception {
      // Given
      OffsetDateTime bannedUntil = OffsetDateTime.parse("2026-12-31T00:00:00Z");
      when(authService.login(any())).thenThrow(new AccountBannedException(bannedUntil));
      String requestJson =
          """
          { "email": "banned@example.com", "password": "12345678a" }
          """;

      // When / Then
      mockMvc
          .perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value(403))
          .andExpect(jsonPath("$.error").value("Account Banned"))
          .andExpect(jsonPath("$.message").value(containsString("banned until")));
    }

    @Test
    @DisplayName("shouldReturn400_whenRegisterThrowsValidationExceptionForDuplicateEmail")
    void shouldReturn400_whenRegisterThrowsValidationExceptionForDuplicateEmail() throws Exception {
      // Given — TC_AUTH_15: registering with an email that already exists
      org.mockito.Mockito.doThrow(new ValidationException("Email already exists"))
          .when(authService)
          .register(any(), any());
      String metadataJson =
          """
          {
            "email": "already-registered@example.com",
            "password": "123456",
            "fullname": "Someone"
          }
          """;
      MockMultipartFile metadata =
          new MockMultipartFile(
              "metadata", "metadata", MediaType.APPLICATION_JSON_VALUE, metadataJson.getBytes());

      // When / Then
      mockMvc
          .perform(multipart(REGISTER_URL).file(metadata))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(400))
          .andExpect(jsonPath("$.message").value("Email already exists"));
    }
  }

  // =====================================================================
  // Malformed requests — bodies that never reach Bean Validation because they fail to
  // deserialize or negotiate content type at all.
  // =====================================================================

  @Nested
  @DisplayName("Malformed requests")
  class MalformedRequestTests {

    @Test
    @DisplayName("shouldReturn400_whenLoginBodyIsMalformedJson")
    void shouldReturn400_whenLoginBodyIsMalformedJson() throws Exception {
      // Given — syntactically invalid JSON (unterminated object)
      String malformedJson = "{ \"email\": \"admin1@socialapp.com\", \"password\": ";

      // When / Then — HttpMessageNotReadableException now has its own @ExceptionHandler in
      // GlobalExceptionHandler (previously fell through to the generic Exception handler and
      // was misreported as 500).
      mockMvc
          .perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content(malformedJson))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    @DisplayName("shouldReturn415_whenLoginCalledWithoutContentType")
    void shouldReturn415_whenLoginCalledWithoutContentType() throws Exception {
      // Given
      String requestJson =
          """
          { "email": "admin1@socialapp.com", "password": "12345678a" }
          """;

      // When / Then — with no Content-Type header, Spring cannot select an HttpMessageConverter
      // for the @RequestBody parameter and raises HttpMediaTypeNotSupportedException, which now
      // has its own @ExceptionHandler (previously fell through to the generic Exception handler
      // and was misreported as 500).
      mockMvc
          .perform(post(LOGIN_URL).content(requestJson))
          .andExpect(status().isUnsupportedMediaType())
          .andExpect(jsonPath("$.message").value("Content-Type must be application/json"));
    }

    @Test
    @DisplayName("shouldReturn405_whenMethodIsNotSupported")
    void shouldReturn405_whenMethodIsNotSupported() throws Exception {
      // Given — /auth/login exists but only answers POST

      // When / Then — HttpRequestMethodNotSupportedException now has its own @ExceptionHandler
      // (previously fell through to the generic Exception handler and was misreported as 500).
      // RFC 9110 §15.5.6 makes the Allow header mandatory on a 405, hence the header assertion.
      mockMvc
          .perform(delete(LOGIN_URL))
          .andExpect(status().isMethodNotAllowed())
          .andExpect(jsonPath("$.message").value(containsString("DELETE")))
          .andExpect(header().string("Allow", containsString("POST")));
    }
  }

  // =====================================================================
  // POST /v1/api/auth/register
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/auth/register")
  class RegisterTests {

    @Test
    @DisplayName("shouldReturn200_whenRegisterPayloadIsValid_postmanHappyPath")
    void shouldReturn200_whenRegisterPayloadIsValid_postmanHappyPath() throws Exception {
      // Given — exact metadata body from the Postman "[API] Register" request
      String metadataJson =
          """
          {
            "email": "myemail@example.com",
            "password": "123456",
            "fullname": "MyUserFullname"
          }
          """;
      MockMultipartFile metadata =
          new MockMultipartFile(
              "metadata", "metadata", MediaType.APPLICATION_JSON_VALUE, metadataJson.getBytes());

      // When / Then
      mockMvc.perform(multipart(REGISTER_URL).file(metadata)).andExpect(status().isOk());

      verify(authService).register(any(), any());
    }

    @Test
    @DisplayName("shouldReturn200_whenRegisterIncludesAValidProfilePicture")
    void shouldReturn200_whenRegisterIncludesAValidProfilePicture() throws Exception {
      // Given — TC_AUTH_02: metadata + a small valid image part
      String metadataJson =
          """
          {
            "email": "withpicture@example.com",
            "password": "123456",
            "fullname": "Has Picture"
          }
          """;
      MockMultipartFile metadata =
          new MockMultipartFile(
              "metadata", "metadata", MediaType.APPLICATION_JSON_VALUE, metadataJson.getBytes());
      MockMultipartFile profilePicture =
          new MockMultipartFile(
              "profilePicture", "avatar.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[] {1, 2, 3, 4});

      // When / Then
      mockMvc
          .perform(multipart(REGISTER_URL).file(metadata).file(profilePicture))
          .andExpect(status().isOk());

      verify(authService).register(any(), any());
    }

    // TC_AUTH_21 (oversized profile picture -> 413 via MaxUploadSizeExceededException, see
    // GlobalExceptionHandler) is a real spring.servlet.multipart.max-file-size (20MB) boundary,
    // but is NOT observable from a @WebMvcTest: MockMvc's MockMultipartFile requests bypass the
    // embedded servlet container's real multipart parser entirely (confirmed empirically — a
    // 21MB MockMultipartFile still returns 200 here), so no size limit is ever enforced. Proving
    // this boundary requires a full @SpringBootTest(webEnvironment = RANDOM_PORT) hitting a real
    // Tomcat instance instead, which is out of scope for this Controller slice test.

    @ParameterizedTest(name = "shouldReturn422_whenRegisterFieldsInvalid: {0}")
    @DisplayName("shouldReturn422_whenRegisterFieldsAreInvalid")
    @CsvSource({
      "'', 123456, MyUserFullname", // TC_AUTH_29: missing email
      "not-an-email, 123456, MyUserFullname", // TC_AUTH_29: malformed email
      "myemail@example.com, '', MyUserFullname", // TC_AUTH_29: missing password
      "myemail@example.com, 123, MyUserFullname", // TC_AUTH_29: password < 6 chars
      "myemail@example.com, 123456, ''", // TC_AUTH_29: missing fullname
    })
    void shouldReturn422_whenRegisterFieldsAreInvalid(
        String email, String password, String fullname) throws Exception {
      // Given
      String metadataJson =
          objectMapper.writeValueAsString(new RegisterMetadata(email, password, fullname));
      MockMultipartFile metadata =
          new MockMultipartFile(
              "metadata", "metadata", MediaType.APPLICATION_JSON_VALUE, metadataJson.getBytes());

      // When / Then
      mockMvc
          .perform(multipart(REGISTER_URL).file(metadata))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn422WithFieldDetails_whenRegisterPasswordIsTooShort")
    void shouldReturn422WithFieldDetails_whenRegisterPasswordIsTooShort() throws Exception {
      // Given
      String metadataJson =
          objectMapper.writeValueAsString(
              new RegisterMetadata("myemail@example.com", "123", "MyUserFullname"));
      MockMultipartFile metadata =
          new MockMultipartFile(
              "metadata", "metadata", MediaType.APPLICATION_JSON_VALUE, metadataJson.getBytes());

      // When / Then — asserts the actual error body shape, not just the status code
      mockMvc
          .perform(multipart(REGISTER_URL).file(metadata))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.message").value("Invalid request parameters or payload"))
          .andExpect(jsonPath("$.details[0]").value(containsString("Property password")));
    }

    private record RegisterMetadata(String email, String password, String fullname) {}
  }

  // =====================================================================
  // POST /v1/api/auth/login
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/auth/login")
  class LoginTests {

    @Test
    @DisplayName("shouldReturn200AndTokens_whenLoginPayloadIsValid_postmanHappyPath")
    void shouldReturn200AndTokens_whenLoginPayloadIsValid_postmanHappyPath() throws Exception {
      // Given — exact body from the Postman "[API] Login" request
      when(authService.login(any())).thenReturn(sampleAuthResponse());
      String requestJson =
          """
          {
              "email": "admin1@socialapp.com",
              "password": "12345678a"
          }
          """;

      // When / Then
      mockMvc
          .perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.accessToken").value("access-token-abc"))
          .andExpect(jsonPath("$.refreshToken").value("refresh-token-xyz"))
          .andExpect(jsonPath("$.tokenType").value("Bearer"))
          .andExpect(jsonPath("$.expiresIn").value(3600));
    }

    @ParameterizedTest(name = "shouldReturn422_whenLoginFieldsInvalid: {0}")
    @DisplayName("shouldReturn422_whenLoginFieldsAreInvalid")
    @CsvSource({
      "'', 12345678a", // TC_AUTH_29: missing email
      "not-an-email, 12345678a", // TC_AUTH_29: malformed email
      "admin1@socialapp.com, ''", // TC_AUTH_29: missing password
    })
    void shouldReturn422_whenLoginFieldsAreInvalid(String email, String password) throws Exception {
      // Given
      String requestJson = "{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password);

      // When / Then
      mockMvc
          .perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn422WithFieldDetails_whenLoginEmailIsMissing")
    void shouldReturn422WithFieldDetails_whenLoginEmailIsMissing() throws Exception {
      // Given
      String requestJson =
          """
          { "email": "", "password": "12345678a" }
          """;

      // When / Then — asserts the actual error body shape, not just the status code
      mockMvc
          .perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.message").value("Invalid request parameters or payload"))
          .andExpect(jsonPath("$.details[0]").value(containsString("Property email")))
          .andExpect(jsonPath("$.details[0]").value(containsString("Email is required")));
    }
  }

  // =====================================================================
  // POST /v1/api/auth/refresh
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/auth/refresh")
  class RefreshTests {

    @Test
    @DisplayName("shouldReturn200AndNewTokens_whenRefreshTokenIsValid_postmanHappyPath")
    void shouldReturn200AndNewTokens_whenRefreshTokenIsValid_postmanHappyPath() throws Exception {
      // Given
      when(authService.refresh(any())).thenReturn(sampleAuthResponse());
      String requestJson =
          """
          { "refreshToken": "a-valid-refresh-token" }
          """;

      // When / Then
      mockMvc
          .perform(post(REFRESH_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.accessToken").value("access-token-abc"));
    }

    @ParameterizedTest
    @DisplayName("shouldReturn422_whenRefreshTokenIsBlank")
    @ValueSource(strings = {"", "   "})
    void shouldReturn422_whenRefreshTokenIsBlank(String blankToken) throws Exception {
      // Given
      String requestJson = "{\"refreshToken\": \"%s\"}".formatted(blankToken);

      // When / Then
      mockMvc
          .perform(post(REFRESH_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }
  }

  // =====================================================================
  // POST /v1/api/auth/forgot-password
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/auth/forgot-password")
  class ForgotPasswordTests {

    @Test
    @DisplayName("shouldReturn200_whenEmailIsValid_postmanHappyPath")
    void shouldReturn200_whenEmailIsValid_postmanHappyPath() throws Exception {
      // Given
      String requestJson =
          """
          { "email": "myemail@example.com" }
          """;

      // When / Then
      mockMvc
          .perform(
              post(FORGOT_PASSWORD_URL)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "shouldReturn422_whenEmailInvalid: {0}")
    @DisplayName("shouldReturn422_whenEmailIsBlankOrMalformed")
    @ValueSource(strings = {"", "not-an-email"})
    void shouldReturn422_whenEmailIsBlankOrMalformed(String email) throws Exception {
      // Given
      String requestJson = "{\"email\": \"%s\"}".formatted(email);

      // When / Then
      mockMvc
          .perform(
              post(FORGOT_PASSWORD_URL)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }
  }

  // =====================================================================
  // POST /v1/api/auth/reset-password
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/auth/reset-password")
  class ResetPasswordTests {

    @Test
    @DisplayName("shouldReturn200_whenTokenAndNewPasswordAreValid_postmanHappyPath")
    void shouldReturn200_whenTokenAndNewPasswordAreValid_postmanHappyPath() throws Exception {
      // Given — exact body from the Postman "[API] Reset Password" request
      String requestJson =
          """
          {
              "token": "_viNzMNXf7_2I52TG9VWETZPD1WklK73gqYED3uvK1o",
              "newPassword": "123456"
          }
          """;

      // When / Then
      mockMvc
          .perform(
              post(RESET_PASSWORD_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isOk());
    }

    static Stream<Arguments> invalidResetPasswordPayloads() {
      return Stream.of(
          Arguments.of("", "123456"), // TC_AUTH_29: missing token
          Arguments.of("a-valid-token", ""), // TC_AUTH_29: missing newPassword
          Arguments.of("a-valid-token", "123") // TC_AUTH_29: newPassword < 6 chars
          );
    }

    @ParameterizedTest(
        name = "shouldReturn422_whenResetPasswordFieldsInvalid: token={0}, newPassword={1}")
    @DisplayName("shouldReturn422_whenResetPasswordFieldsAreInvalid")
    @MethodSource("invalidResetPasswordPayloads")
    void shouldReturn422_whenResetPasswordFieldsAreInvalid(String token, String newPassword)
        throws Exception {
      // Given
      String requestJson =
          "{\"token\": \"%s\", \"newPassword\": \"%s\"}".formatted(token, newPassword);

      // When / Then
      mockMvc
          .perform(
              post(RESET_PASSWORD_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }
  }

  // =====================================================================
  // POST /v1/api/auth/verify-email
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/auth/verify-email")
  class VerifyEmailTests {

    @Test
    @DisplayName("shouldReturn200_whenTokenIsValid_postmanHappyPath")
    void shouldReturn200_whenTokenIsValid_postmanHappyPath() throws Exception {
      // Given — exact body from the Postman "[API] Verify Email" request
      String requestJson =
          """
          { "token": "mcBcjqZXhG_CQPl79Xf4jOywb_C7VqiQoZ5Vq2ttGdE" }
          """;

      // When / Then
      mockMvc
          .perform(
              post(VERIFY_EMAIL_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isOk());
    }

    @ParameterizedTest
    @DisplayName("shouldReturn422_whenTokenIsBlank")
    @ValueSource(strings = {"", "   "})
    void shouldReturn422_whenTokenIsBlank(String blankToken) throws Exception {
      // Given
      String requestJson = "{\"token\": \"%s\"}".formatted(blankToken);

      // When / Then
      mockMvc
          .perform(
              post(VERIFY_EMAIL_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }
  }

  // =====================================================================
  // POST /v1/api/auth/magic-link
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/auth/magic-link")
  class RequestMagicLinkTests {

    @Test
    @DisplayName("shouldReturn200_whenEmailIsValid_postmanHappyPath")
    void shouldReturn200_whenEmailIsValid_postmanHappyPath() throws Exception {
      // Given — exact body from the Postman "[API] Request Magic Link" request
      String requestJson =
          """
          { "email": "myemail1@example.com" }
          """;

      // When / Then
      mockMvc
          .perform(
              post(MAGIC_LINK_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "shouldReturn422_whenEmailInvalid: {0}")
    @DisplayName("shouldReturn422_whenEmailIsBlankOrMalformed")
    @ValueSource(strings = {"", "not-an-email"})
    void shouldReturn422_whenEmailIsBlankOrMalformed(String email) throws Exception {
      // Given
      String requestJson = "{\"email\": \"%s\"}".formatted(email);

      // When / Then
      mockMvc
          .perform(
              post(MAGIC_LINK_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }
  }

  // =====================================================================
  // POST /v1/api/auth/magic-link/login
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/auth/magic-link/login")
  class LoginWithMagicLinkTests {

    @Test
    @DisplayName("shouldReturn200AndTokens_whenTokenIsValid_postmanHappyPath")
    void shouldReturn200AndTokens_whenTokenIsValid_postmanHappyPath() throws Exception {
      // Given — exact body from the Postman "[API] Login with Magic Link" request
      when(authService.loginWithMagicLink(any())).thenReturn(sampleAuthResponse());
      String requestJson =
          """
          { "token": "PLQzlZL1t3RoQnT2jR5lE18XVM5rSsS1cmje3MDdgO4" }
          """;

      // When / Then
      mockMvc
          .perform(
              post(MAGIC_LINK_LOGIN_URL)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @ParameterizedTest
    @DisplayName("shouldReturn422_whenTokenIsBlank")
    @ValueSource(strings = {"", "   "})
    void shouldReturn422_whenTokenIsBlank(String blankToken) throws Exception {
      // Given
      String requestJson = "{\"token\": \"%s\"}".formatted(blankToken);

      // When / Then
      mockMvc
          .perform(
              post(MAGIC_LINK_LOGIN_URL)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }
  }

  // =====================================================================
  // POST /v1/api/auth/logout
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/auth/logout")
  class LogoutTests {

    @Test
    @DisplayName("shouldReturn200_whenRefreshTokenIsValid_postmanHappyPath")
    void shouldReturn200_whenRefreshTokenIsValid_postmanHappyPath() throws Exception {
      // Given — exact body from the Postman "[API] Logout" request
      String requestJson =
          """
          { "refreshToken": "a-valid-refresh-token" }
          """;

      // When / Then
      mockMvc
          .perform(post(LOGOUT_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isOk());
    }

    @ParameterizedTest
    @DisplayName("shouldReturn422_whenRefreshTokenIsBlank")
    @ValueSource(strings = {"", "   "})
    void shouldReturn422_whenRefreshTokenIsBlank(String blankToken) throws Exception {
      // Given
      String requestJson = "{\"refreshToken\": \"%s\"}".formatted(blankToken);

      // When / Then
      mockMvc
          .perform(post(LOGOUT_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }
  }
}
