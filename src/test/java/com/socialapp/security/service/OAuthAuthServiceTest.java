package com.socialapp.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.github.service.GithubApiClient;
import com.socialapp.security.dto.AuthResponseDto;
import com.socialapp.security.dto.OAuthUrlResponseDto;
import com.socialapp.security.entity.AuthProvider;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.exception.AccountBannedException;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link OAuthAuthService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 2.1.3 BDD Given/When/Then) — extracted from {@code AuthServiceTest}
 * when {@code AuthService}'s 4 OAuth methods were split out (SRP), closing a pre-existing coverage
 * gap since neither {@code AuthServiceTest} nor {@code AuthControllerTest} ever tested them.
 */
@ExtendWith(MockitoExtension.class)
class OAuthAuthServiceTest {

  private static final Integer USER_ID = 1;
  private static final String EMAIL = "user@example.com";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Mock private UserRepository userRepository;
  @Mock private TokenService tokenService;
  @Mock private GoogleApiClient googleApiClient;
  @Mock private GithubApiClient githubApiClient;

  @InjectMocks private OAuthAuthService oAuthAuthService;

  @Captor private ArgumentCaptor<UserEntity> userCaptor;

  private static UserEntity existingUser(AuthProvider provider, boolean banned) {
    UserEntity user = new UserEntity();
    user.setId(USER_ID);
    user.setEmail(EMAIL);
    user.setAuthProvider(provider);
    if (banned) {
      user.setBannedUntil(OffsetDateTime.now().plusDays(1));
    }
    return user;
  }

  private static JsonNode json(String jsonText) {
    try {
      return OBJECT_MAPPER.readTree(jsonText);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // =====================================================================
  // getGoogleOAuthUrl / getGithubOAuthUrl
  // =====================================================================

  @Test
  @DisplayName("getGoogleOAuthUrl should return the URL built by GoogleApiClient")
  void getGoogleOAuthUrl_shouldReturnClientUrl() {
    when(googleApiClient.getOAuthUrl()).thenReturn("https://accounts.google.com/o/oauth2/...");

    OAuthUrlResponseDto response = oAuthAuthService.getGoogleOAuthUrl();

    assertThat(response.getOauthUrl()).isEqualTo("https://accounts.google.com/o/oauth2/...");
  }

  @Test
  @DisplayName("getGithubOAuthUrl should return the URL built by GithubApiClient")
  void getGithubOAuthUrl_shouldReturnClientUrl() {
    when(githubApiClient.getOAuthUrl()).thenReturn("https://github.com/login/oauth/authorize/...");

    OAuthUrlResponseDto response = oAuthAuthService.getGithubOAuthUrl();

    assertThat(response.getOauthUrl()).isEqualTo("https://github.com/login/oauth/authorize/...");
  }

  // =====================================================================
  // loginWithGoogle
  // =====================================================================

  @Nested
  @DisplayName("loginWithGoogle")
  class LoginWithGoogleTests {

    @Test
    @DisplayName("should reject when Google's user-info response has no email")
    void shouldThrowValidationException_whenNoEmailInResponse() {
      when(googleApiClient.exchangeCodeForToken("code")).thenReturn("access-token");
      when(googleApiClient.getUserInfo("access-token")).thenReturn(json("{\"sub\": \"123\"}"));

      assertThatThrownBy(() -> oAuthAuthService.loginWithGoogle("code"))
          .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("should create a new local-less user on first login")
    void shouldCreateNewUser_whenEmailNotFound() {
      when(googleApiClient.exchangeCodeForToken("code")).thenReturn("access-token");
      when(googleApiClient.getUserInfo("access-token"))
          .thenReturn(
              json(
                  "{\"email\": \""
                      + EMAIL
                      + "\", \"name\": \"Jane\", \"picture\": \"pic.png\", \"sub\": \"g-123\"}"));
      when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
      when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
      when(tokenService.issueTokens(any(), anyBoolean(), anyBoolean()))
          .thenReturn(new AuthResponseDto(null, null, null, 0, false, false));

      oAuthAuthService.loginWithGoogle("code");

      verify(userRepository).save(userCaptor.capture());
      UserEntity saved = userCaptor.getValue();
      assertThat(saved.getEmail()).isEqualTo(EMAIL);
      assertThat(saved.getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
      assertThat(saved.isEmailVerified()).isTrue();
      verify(tokenService).issueTokens(saved, false, true);
    }

    @Test
    @DisplayName("should reject a banned existing user")
    void shouldThrowAccountBannedException_whenExistingUserIsBanned() {
      when(googleApiClient.exchangeCodeForToken("code")).thenReturn("access-token");
      when(googleApiClient.getUserInfo("access-token"))
          .thenReturn(json("{\"email\": \"" + EMAIL + "\"}"));
      when(userRepository.findByEmailIgnoreCase(EMAIL))
          .thenReturn(Optional.of(existingUser(AuthProvider.GOOGLE, true)));

      assertThatThrownBy(() -> oAuthAuthService.loginWithGoogle("code"))
          .isInstanceOf(AccountBannedException.class);
    }

    @Test
    @DisplayName("should auto-link an existing LOCAL account")
    void shouldAutoLinkExistingLocalAccount() {
      UserEntity localUser = existingUser(AuthProvider.LOCAL, false);
      when(googleApiClient.exchangeCodeForToken("code")).thenReturn("access-token");
      when(googleApiClient.getUserInfo("access-token"))
          .thenReturn(json("{\"email\": \"" + EMAIL + "\", \"sub\": \"g-123\"}"));
      when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(localUser));
      when(tokenService.issueTokens(any(), anyBoolean(), anyBoolean()))
          .thenReturn(new AuthResponseDto(null, null, null, 0, false, false));

      oAuthAuthService.loginWithGoogle("code");

      assertThat(localUser.getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
      verify(userRepository).save(localUser);
      verify(tokenService).issueTokens(localUser, true, false);
    }

    @Test
    @DisplayName("should not re-link or re-save an already-linked GOOGLE account")
    void shouldNotResave_whenAlreadyLinkedToGoogle() {
      UserEntity googleUser = existingUser(AuthProvider.GOOGLE, false);
      when(googleApiClient.exchangeCodeForToken("code")).thenReturn("access-token");
      when(googleApiClient.getUserInfo("access-token"))
          .thenReturn(json("{\"email\": \"" + EMAIL + "\"}"));
      when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(googleUser));
      when(tokenService.issueTokens(any(), anyBoolean(), anyBoolean()))
          .thenReturn(new AuthResponseDto(null, null, null, 0, false, false));

      oAuthAuthService.loginWithGoogle("code");

      verify(userRepository, never()).save(any());
      verify(tokenService).issueTokens(googleUser, false, false);
    }
  }

  // =====================================================================
  // loginWithGithub
  // =====================================================================

  @Nested
  @DisplayName("loginWithGithub")
  class LoginWithGithubTests {

    @Test
    @DisplayName("should reject when GitHub has no verified primary email")
    void shouldThrowValidationException_whenNoVerifiedPrimaryEmail() {
      when(githubApiClient.exchangeCodeForToken("code")).thenReturn("access-token");
      when(githubApiClient.getAuthenticatedUser("access-token")).thenReturn(json("{\"id\": 42}"));
      when(githubApiClient.getUserEmails("access-token"))
          .thenReturn(json("[{\"email\": \"other@example.com\", \"primary\": false}]"));

      assertThatThrownBy(() -> oAuthAuthService.loginWithGithub("code"))
          .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("should create a new user from the verified primary email")
    void shouldCreateNewUser_whenEmailNotFound() {
      when(githubApiClient.exchangeCodeForToken("code")).thenReturn("access-token");
      when(githubApiClient.getAuthenticatedUser("access-token"))
          .thenReturn(json("{\"id\": 42, \"name\": \"Jane\", \"avatar_url\": \"pic.png\"}"));
      when(githubApiClient.getUserEmails("access-token"))
          .thenReturn(
              json("[{\"email\": \"" + EMAIL + "\", \"primary\": true, \"verified\": true}]"));
      when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
      when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
      when(tokenService.issueTokens(any(), anyBoolean(), anyBoolean()))
          .thenReturn(new AuthResponseDto(null, null, null, 0, false, false));

      oAuthAuthService.loginWithGithub("code");

      verify(userRepository).save(userCaptor.capture());
      UserEntity saved = userCaptor.getValue();
      assertThat(saved.getEmail()).isEqualTo(EMAIL);
      assertThat(saved.getAuthProvider()).isEqualTo(AuthProvider.GITHUB);
      verify(tokenService).issueTokens(saved, false, true);
    }

    @Test
    @DisplayName("should reject a banned existing user")
    void shouldThrowAccountBannedException_whenExistingUserIsBanned() {
      when(githubApiClient.exchangeCodeForToken("code")).thenReturn("access-token");
      when(githubApiClient.getAuthenticatedUser("access-token")).thenReturn(json("{\"id\": 42}"));
      when(githubApiClient.getUserEmails("access-token"))
          .thenReturn(
              json("[{\"email\": \"" + EMAIL + "\", \"primary\": true, \"verified\": true}]"));
      when(userRepository.findByEmailIgnoreCase(EMAIL))
          .thenReturn(Optional.of(existingUser(AuthProvider.GITHUB, true)));

      assertThatThrownBy(() -> oAuthAuthService.loginWithGithub("code"))
          .isInstanceOf(AccountBannedException.class);
    }

    @Test
    @DisplayName("should auto-link an existing LOCAL account")
    void shouldAutoLinkExistingLocalAccount() {
      UserEntity localUser = existingUser(AuthProvider.LOCAL, false);
      when(githubApiClient.exchangeCodeForToken("code")).thenReturn("access-token");
      when(githubApiClient.getAuthenticatedUser("access-token")).thenReturn(json("{\"id\": 42}"));
      when(githubApiClient.getUserEmails("access-token"))
          .thenReturn(
              json("[{\"email\": \"" + EMAIL + "\", \"primary\": true, \"verified\": true}]"));
      when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(localUser));
      when(tokenService.issueTokens(any(), anyBoolean(), anyBoolean()))
          .thenReturn(new AuthResponseDto(null, null, null, 0, false, false));

      oAuthAuthService.loginWithGithub("code");

      assertThat(localUser.getAuthProvider()).isEqualTo(AuthProvider.GITHUB);
      verify(userRepository).save(localUser);
      verify(tokenService).issueTokens(localUser, true, false);
    }
  }
}
