package com.socialapp.posts.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialapp.common.exception.ValidationException;
import com.socialapp.posts.dto.LocationResolutionResponseDto;
import com.socialapp.posts.entity.enums.LocationType;
import com.socialapp.posts.service.LocationResolutionService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link LocationController}, per ISTQB CTFL v4.0.1 Section
 * 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link LocationResolutionService} is
 * mocked.
 *
 * <p>The controller carries {@code @Valid}, but {@link
 * com.socialapp.posts.dto.LocationResolutionRequestDto} is a record with zero constraint
 * annotations on any field — so {@code @Valid} has nothing to check and never produces a 422
 * here. The "either query or both latitude/longitude" rule, and all geocoding-failure cases,
 * live entirely in {@code LocationResolutionService} and surface only via {@link
 * ExceptionMappingTests} against the mocked service.
 */
@WebMvcTest(LocationController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class LocationControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private LocationResolutionService locationResolutionService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private UserRepository userRepository;

  private static final String RESOLVE_URL = "/v1/api/posts/locations/resolve";
  private static final String VALID_TOKEN = "a-valid-jwt-token";

  @BeforeEach
  void setUpDefaultUser() {
    UserEntity currentUser = new UserEntity();
    currentUser.setId(1);
    currentUser.setEmail("poster@example.com");
    currentUser.setUsername("poster");
    currentUser.setFullName("Poster One");
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

  @Nested
  @DisplayName("POST /v1/api/posts/locations/resolve")
  class ResolveTests {

    @Test
    @DisplayName("shouldReturn200_whenQueryIsProvided_happyPath")
    void shouldReturn200_whenQueryIsProvided_happyPath() throws Exception {
      // Given
      when(locationResolutionService.resolve(any()))
          .thenReturn(
              List.of(
                  new LocationResolutionResponseDto(
                      "place-123",
                      LocationType.PLACE,
                      null,
                      "https://maps.google.com/?q=place-123")));
      String requestJson =
          """
          { "query": "Hanoi" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(post(RESOLVE_URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].googlePlaceId").value("place-123"))
          .andExpect(jsonPath("$[0].locationType").value("PLACE"));
    }

    @Test
    @DisplayName("shouldReturn200_whenLatitudeAndLongitudeAreProvided_happyPath")
    void shouldReturn200_whenLatitudeAndLongitudeAreProvided_happyPath() throws Exception {
      // Given
      when(locationResolutionService.resolve(any())).thenReturn(List.of());
      String requestJson =
          """
          { "latitude": 21.0285, "longitude": 105.8542 }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(post(RESOLVE_URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("shouldReturn400_whenNeitherQueryNorCoordinatesAreProvided")
    void shouldReturn400_whenNeitherQueryNorCoordinatesAreProvided() throws Exception {
      // Given
      doThrow(new ValidationException("Either query or both latitude and longitude are required"))
          .when(locationResolutionService)
          .resolve(any());
      String requestJson = "{}";

      // When / Then
      mockMvc
          .perform(
              authed(post(RESOLVE_URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isBadRequest())
          .andExpect(
              jsonPath("$.message")
                  .value("Either query or both latitude and longitude are required"));
    }

    @Test
    @DisplayName("shouldReturn400_whenLocationCannotBeResolved")
    void shouldReturn400_whenLocationCannotBeResolved() throws Exception {
      // Given
      doThrow(new ValidationException("Could not resolve location: asdkfjhalksdjf"))
          .when(locationResolutionService)
          .resolve(any());
      String requestJson =
          """
          { "query": "asdkfjhalksdjf" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(post(RESOLVE_URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // Given
      String requestJson =
          """
          { "query": "Hanoi" }
          """;

      // When / Then
      mockMvc
          .perform(post(RESOLVE_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnauthorized());
    }
  }
}
