package com.socialapp.security.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.knowledge.entity.enums.PrimaryRole;
import com.socialapp.knowledge.entity.enums.SeniorityLevel;
import com.socialapp.moderation.service.BanDetailsService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.dto.PublicProfileResponse;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;
import com.socialapp.security.service.ProfileService;

/**
 * System/API integration tests for {@link PublicProfileController}, per ISTQB CTFL v4.0.1 Section
 * 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}.
 *
 * <p>The load-bearing case here is the one asserting on the <b>absent</b> fields. This endpoint is
 * the app's first "look at somebody else" surface, and the failure mode it guards against —
 * reusing {@code UserResponse} and shipping every user's email address to every reader — produces
 * a perfectly working endpoint, so only a test that names the forbidden fields can catch it.
 *
 * <p>Auth simulation follows {@code ProfileControllerTest}: a real {@link JwtAuthenticationFilter}
 * with a stubbed {@link JwtProvider}, because {@code SecurityUtils} only recognises a {@link
 * UserEntity} principal.
 */
@WebMvcTest(PublicProfileController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class PublicProfileControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private ProfileService profileService;
  @MockBean private JwtProvider jwtProvider;

  @MockBean
  private BanDetailsService
      banDetailsService; // JwtAuthenticationFilter builds the banned-account 403 through it

  @MockBean private UserRepository userRepository;

  private static final String VALID_TOKEN = "a-valid-jwt-token";
  private static final Integer SUBJECT_ID = 42;
  private static final String SUBJECT_USERNAME = "ada";

  private UserEntity currentUser;

  @BeforeEach
  void setUpDefaultUser() {
    currentUser = new UserEntity();
    currentUser.setId(1);
    currentUser.setEmail("viewer@example.com");
    currentUser.setUsername("viewer");
    currentUser.setRole(UserRole.USER);
    currentUser.setEmailVerified(true);
  }

  private void mockAuthenticatedAs(UserEntity user) {
    when(jwtProvider.isTokenValid(VALID_TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(VALID_TOKEN)).thenReturn(user.getEmail());
    when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
  }

  private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request) {
    return request.header("Authorization", "Bearer " + VALID_TOKEN);
  }

  private static String url(String username) {
    return "/v1/api/users/" + username + "/profile";
  }

  @Nested
  @DisplayName("GET /v1/api/users/{username}/profile")
  class GetPublicProfileTests {

    @Test
    @DisplayName("shouldReturn200AndPublicFields_whenTheUserExists_happyPath")
    void shouldReturn200AndPublicFields() throws Exception {
      // Given
      mockAuthenticatedAs(currentUser);
      when(profileService.getPublicProfile(SUBJECT_USERNAME))
          .thenReturn(
              new PublicProfileResponse(
                  SUBJECT_ID,
                  "ada",
                  "Ada Lovelace",
                  "http://cdn.example.com/ada.png",
                  null,
                  120,
                  OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                  2,
                  "Contributor",
                  100,
                  1000,
                  "Senior Backend Engineer",
                  PrimaryRole.BACKEND,
                  SeniorityLevel.SENIOR,
                  8,
                  List.of("Spring Boot", "Caching")));

      // When / Then — levelName and nextLevelMin travel with the score, because the design system
      // forbids the client from deriving a level from a number it also holds.
      mockMvc
          .perform(authed(get(url(SUBJECT_USERNAME))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(SUBJECT_ID))
          .andExpect(jsonPath("$.username").value("ada"))
          .andExpect(jsonPath("$.fullName").value("Ada Lovelace"))
          .andExpect(jsonPath("$.eliteScore").value(120))
          .andExpect(jsonPath("$.level").value(2))
          .andExpect(jsonPath("$.levelName").value("Contributor"))
          .andExpect(jsonPath("$.currentLevelMin").value(100))
          .andExpect(jsonPath("$.nextLevelMin").value(1000))
          .andExpect(jsonPath("$.verifiedSkills[0]").value("Spring Boot"))
          .andExpect(jsonPath("$.verifiedSkills[1]").value("Caching"))
          // The role line: four fields, on a payload that is built once per request anyway
          .andExpect(jsonPath("$.jobTitle").value("Senior Backend Engineer"))
          .andExpect(jsonPath("$.primaryRole").value("BACKEND"))
          .andExpect(jsonPath("$.seniorityLevel").value("SENIOR"))
          .andExpect(jsonPath("$.yearsOfExperience").value(8))
          // ...and the half of the professional profile that stays private. These were declared
          // so the Gemini explainer could tailor its answers, not to be published on a page
          // anybody can open.
          .andExpect(jsonPath("$.workHistory").doesNotExist())
          .andExpect(jsonPath("$.interestedDomains").doesNotExist())
          .andExpect(jsonPath("$.knownTechStack").doesNotExist())
          .andExpect(jsonPath("$.explanationStyle").doesNotExist());
    }

    @Test
    @DisplayName("shouldOmitTheRoleLine_whenTheUserHasNoProfessionalProfile")
    void shouldOmitRoleLineWhenAbsent() throws Exception {
      // Given - most accounts have never filled the form in, and the client leaves the slot out
      // rather than rendering an empty line
      mockAuthenticatedAs(currentUser);
      when(profileService.getPublicProfile(SUBJECT_USERNAME))
          .thenReturn(
              new PublicProfileResponse(
                  SUBJECT_ID,
                  "ada",
                  "Ada Lovelace",
                  null,
                  null,
                  0,
                  OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                  1,
                  "Newcomer",
                  0,
                  100,
                  null,
                  null,
                  null,
                  null,
                  List.of()));

      // When / Then
      mockMvc
          .perform(authed(get(url(SUBJECT_USERNAME))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.jobTitle").doesNotExist())
          .andExpect(jsonPath("$.primaryRole").doesNotExist())
          .andExpect(jsonPath("$.seniorityLevel").doesNotExist())
          .andExpect(jsonPath("$.yearsOfExperience").doesNotExist());
    }

    @Test
    @DisplayName("shouldNotExposeEmailOrRole_whenReadingSomebodyElsesProfile")
    void shouldNotExposeEmailOrRole() throws Exception {
      // Given
      mockAuthenticatedAs(currentUser);
      when(profileService.getPublicProfile(SUBJECT_USERNAME))
          .thenReturn(
              new PublicProfileResponse(
                  SUBJECT_ID,
                  "ada",
                  "Ada Lovelace",
                  null,
                  null,
                  0,
                  OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                  1,
                  "Newcomer",
                  0,
                  100,
                  null,
                  null,
                  null,
                  null,
                  List.of()));

      // When / Then — the fields that would turn this endpoint into a mass email disclosure
      mockMvc
          .perform(authed(get(url(SUBJECT_USERNAME))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.email").doesNotExist())
          .andExpect(jsonPath("$.emailVerified").doesNotExist())
          .andExpect(jsonPath("$.role").doesNotExist())
          .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    @DisplayName("shouldReturn404_whenTheUserDoesNotExist")
    void shouldReturn404_whenUserMissing() throws Exception {
      // Given
      mockAuthenticatedAs(currentUser);
      when(profileService.getPublicProfile(SUBJECT_USERNAME))
          .thenThrow(new NotFoundException("User not found: " + SUBJECT_USERNAME));

      // When / Then
      mockMvc.perform(authed(get(url(SUBJECT_USERNAME)))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn200_whenCalledByAGuestWithNoAuthorizationHeader")
    void shouldServeGuests() throws Exception {
      // Given: no Authorization header at all
      when(profileService.getPublicProfile(SUBJECT_USERNAME))
          .thenReturn(
              new PublicProfileResponse(
                  SUBJECT_ID,
                  SUBJECT_USERNAME,
                  "Ada Lovelace",
                  null,
                  null,
                  0,
                  OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                  1,
                  "Newcomer",
                  0,
                  100,
                  null,
                  null,
                  null,
                  null,
                  List.of()));

      // When / Then — this is the endpoint a shared /u/{username} link lands on, so it has to
      // answer someone who has never signed in. Pinning it: a later tightening of SecurityConfig
      // that puts it back behind authentication breaks the product decision, not just a test.
      mockMvc
          .perform(get(url(SUBJECT_USERNAME)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.username").value(SUBJECT_USERNAME))
          .andExpect(jsonPath("$.email").doesNotExist());
    }
  }
}
