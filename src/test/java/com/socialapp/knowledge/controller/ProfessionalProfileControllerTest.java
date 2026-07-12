package com.socialapp.knowledge.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.entity.enums.PrimaryRole;
import com.socialapp.knowledge.entity.enums.SeniorityLevel;
import com.socialapp.knowledge.service.ProfessionalProfileService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link ProfessionalProfileController}, per ISTQB CTFL v4.0.1
 * Section 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link ProfessionalProfileService}
 * is mocked.
 */
@WebMvcTest(ProfessionalProfileController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class ProfessionalProfileControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private ProfessionalProfileService profileService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private UserRepository userRepository;

  private static final String PROFILE_URL = "/v1/api/profile/professional";
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

  private static UserProfessionalProfileEntity sampleProfile() {
    UserProfessionalProfileEntity profile = new UserProfessionalProfileEntity();
    profile.setUserId(1);
    profile.setJobTitle("Backend Engineer");
    profile.setSeniorityLevel(SeniorityLevel.SENIOR);
    profile.setYearsOfExperience(5);
    profile.setPrimaryRole(PrimaryRole.BACKEND);
    return profile;
  }

  // =====================================================================
  // GET /v1/api/profile/professional
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/profile/professional")
  class GetProfileTests {

    @Test
    @DisplayName("shouldReturn200AndProfile_happyPath")
    void shouldReturn200AndProfile_happyPath() throws Exception {
      // Given
      when(profileService.getProfile(currentUser.getId())).thenReturn(sampleProfile());

      // When / Then
      mockMvc
          .perform(authed(get(PROFILE_URL)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.jobTitle").value("Backend Engineer"))
          .andExpect(jsonPath("$.seniorityLevel").value("SENIOR"));
    }

    @Test
    @DisplayName("shouldReturn404_whenProfileDoesNotExist")
    void shouldReturn404_whenProfileDoesNotExist() throws Exception {
      // Given
      when(profileService.getProfile(currentUser.getId()))
          .thenThrow(new NotFoundException("Professional profile not found for user: 1"));

      // When / Then
      mockMvc
          .perform(authed(get(PROFILE_URL)))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value("Professional profile not found for user: 1"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(PROFILE_URL)).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // PUT /v1/api/profile/professional
  // =====================================================================

  @Nested
  @DisplayName("PUT /v1/api/profile/professional")
  class UpdateProfileTests {

    @Test
    @DisplayName("shouldReturn200_whenPayloadIsValid_happyPath")
    void shouldReturn200_whenPayloadIsValid_happyPath() throws Exception {
      // Given
      when(profileService.upsertProfile(eq(currentUser.getId()), any()))
          .thenReturn(sampleProfile());
      String requestJson =
          """
          {
            "jobTitle": "Backend Engineer",
            "seniorityLevel": "SENIOR",
            "yearsOfExperience": 5,
            "primaryRole": "BACKEND"
          }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(put(PROFILE_URL)).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.jobTitle").value("Backend Engineer"));
    }

    @Test
    @DisplayName("shouldReturn422_whenSeniorityLevelIsMissing")
    void shouldReturn422_whenSeniorityLevelIsMissing() throws Exception {
      // Given
      String requestJson =
          """
          { "jobTitle": "Backend Engineer" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(put(PROFILE_URL)).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn422_whenYearsOfExperienceExceedsMax")
    void shouldReturn422_whenYearsOfExperienceExceedsMax() throws Exception {
      // Given
      String requestJson =
          """
          { "seniorityLevel": "SENIOR", "yearsOfExperience": 51 }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(put(PROFILE_URL)).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn422_whenYearsOfExperienceIsNegative")
    void shouldReturn422_whenYearsOfExperienceIsNegative() throws Exception {
      // Given
      String requestJson =
          """
          { "seniorityLevel": "SENIOR", "yearsOfExperience": -1 }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(put(PROFILE_URL)).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // Given
      String requestJson =
          """
          { "seniorityLevel": "SENIOR" }
          """;

      // When / Then
      mockMvc
          .perform(put(PROFILE_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnauthorized());
    }
  }
}
