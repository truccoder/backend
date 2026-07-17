package com.socialapp.knowledge.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.knowledge.dto.CreateTokenResponseDto;
import com.socialapp.knowledge.dto.PersonalAccessTokenResponseDto;
import com.socialapp.knowledge.entity.enums.VaultPermission;
import com.socialapp.knowledge.service.PersonalAccessTokenService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link PersonalAccessTokenController}, per ISTQB CTFL v4.0.1
 * Section 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link PersonalAccessTokenService}
 * is mocked.
 */
@WebMvcTest(PersonalAccessTokenController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class PersonalAccessTokenControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private PersonalAccessTokenService tokenService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private UserRepository userRepository;

  private static final String TOKENS_URL = "/v1/api/tokens";
  private static final String VALID_TOKEN = "a-valid-jwt-token";

  private UserEntity currentUser;

  @BeforeEach
  void setUpDefaultUser() {
    currentUser = new UserEntity();
    currentUser.setId(1);
    currentUser.setEmail("dev@example.com");
    currentUser.setUsername("dev");
    currentUser.setFullName("Dev One");
    currentUser.setRole(UserRole.USER);
    currentUser.setEmailVerified(true);

    when(jwtProvider.isTokenValid(VALID_TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(VALID_TOKEN)).thenReturn(currentUser.getEmail());
    when(userRepository.findByEmailIgnoreCase(currentUser.getEmail()))
        .thenReturn(Optional.of(currentUser));
  }

  private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + VALID_TOKEN);
  }

  // =====================================================================
  // POST /v1/api/tokens
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/tokens")
  class CreateTokenTests {

    @Test
    @DisplayName("shouldReturn200_whenNameOnlyIsProvided_happyPath")
    void shouldReturn200_whenNameOnlyIsProvided_happyPath() throws Exception {
      // Given
      when(tokenService.createToken(eq(currentUser.getId()), any()))
          .thenReturn(
              CreateTokenResponseDto.builder()
                  .id(1)
                  .token("sk_rawtokenvalue")
                  .name("My CLI Token")
                  .expiresAt(null)
                  .build());
      String requestJson =
          """
          { "name": "My CLI Token" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(post(TOKENS_URL)).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(1))
          .andExpect(jsonPath("$.token").value("sk_rawtokenvalue"))
          .andExpect(jsonPath("$.name").value("My CLI Token"));
    }

    @Test
    @DisplayName("shouldReturn200_whenExpiresInDaysAndVaultPermissionAreProvided_happyPath")
    void shouldReturn200_whenExpiresInDaysAndVaultPermissionAreProvided_happyPath()
        throws Exception {
      // Given
      when(tokenService.createToken(eq(currentUser.getId()), any()))
          .thenReturn(
              CreateTokenResponseDto.builder()
                  .id(2)
                  .token("sk_rawtokenvalue2")
                  .name("Sync Token")
                  .expiresAt(OffsetDateTime.parse("2026-08-01T00:00:00Z"))
                  .build());
      String requestJson =
          """
          { "name": "Sync Token", "expiresInDays": 30, "vaultPermission": "BIDIRECTIONAL" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(post(TOKENS_URL)).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(2));
    }

    @Test
    @DisplayName("shouldReturn422_whenNameIsBlank")
    void shouldReturn422_whenNameIsBlank() throws Exception {
      // Given
      String requestJson =
          """
          { "name": "" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(post(TOKENS_URL)).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn422_whenNameIsMissing")
    void shouldReturn422_whenNameIsMissing() throws Exception {
      // Given
      String requestJson = "{}";

      // When / Then
      mockMvc
          .perform(
              authed(post(TOKENS_URL)).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // Given
      String requestJson =
          """
          { "name": "My CLI Token" }
          """;

      // When / Then
      mockMvc
          .perform(post(TOKENS_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/tokens
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/tokens")
  class ListTokensTests {

    @Test
    @DisplayName("shouldReturn200AndTokenList_happyPath")
    void shouldReturn200AndTokenList_happyPath() throws Exception {
      // Given
      PersonalAccessTokenResponseDto token =
          PersonalAccessTokenResponseDto.builder()
              .id(1)
              .name("My CLI Token")
              .vaultPermission(VaultPermission.WRITE_ONLY)
              .build();
      when(tokenService.listTokens(currentUser.getId())).thenReturn(List.of(token));

      // When / Then
      mockMvc
          .perform(authed(get(TOKENS_URL)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].id").value(1))
          .andExpect(jsonPath("$[0].name").value("My CLI Token"))
          .andExpect(jsonPath("$[0].tokenHash").doesNotExist());
    }

    @Test
    @DisplayName("shouldReturn200AndEmptyList_whenNoTokensExist")
    void shouldReturn200AndEmptyList_whenNoTokensExist() throws Exception {
      // Given
      when(tokenService.listTokens(currentUser.getId())).thenReturn(List.of());

      // When / Then
      mockMvc.perform(authed(get(TOKENS_URL))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(TOKENS_URL)).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // DELETE /v1/api/tokens/{tokenId}
  // =====================================================================

  @Nested
  @DisplayName("DELETE /v1/api/tokens/{tokenId}")
  class RevokeTokenTests {

    @Test
    @DisplayName("shouldReturn200_whenTokenIsOwnedByCurrentUser_happyPath")
    void shouldReturn200_whenTokenIsOwnedByCurrentUser_happyPath() throws Exception {
      // When / Then
      mockMvc.perform(authed(delete(TOKENS_URL + "/1"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("shouldReturn404_whenTokenDoesNotExistOrBelongsToAnotherUser")
    void shouldReturn404_whenTokenDoesNotExistOrBelongsToAnotherUser() throws Exception {
      // Given
      doThrow(new NotFoundException("Token not found"))
          .when(tokenService)
          .revokeToken(currentUser.getId(), 999);

      // When / Then
      mockMvc
          .perform(authed(delete(TOKENS_URL + "/999")))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value("Token not found"));
    }

    @Test
    @DisplayName("shouldReturn400_whenTokenIdPathVariableIsNotANumber")
    void shouldReturn400_whenTokenIdPathVariableIsNotANumber() throws Exception {
      // When / Then
      mockMvc
          .perform(authed(delete(TOKENS_URL + "/not-a-number")))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(delete(TOKENS_URL + "/1")).andExpect(status().isUnauthorized());
    }
  }
}
