package com.socialapp.blocks.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialapp.blocks.service.BlockService;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.dto.PublicUserResponse;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link BlockController}, per ISTQB CTFL v4.0.1 Section 2.2.2,
 * using {@code @WebMvcTest} + {@code MockMvc}. {@link BlockService} is mocked.
 */
@WebMvcTest(BlockController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class BlockControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private BlockService blockService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private UserRepository userRepository;

  private static final String BLOCKS_URL = "/v1/api/blocks";
  private static final String VALID_TOKEN = "a-valid-jwt-token";
  private static final Integer TARGET_ID = 42;

  private UserEntity currentUser;

  @BeforeEach
  void setUpDefaultUser() {
    currentUser = new UserEntity();
    currentUser.setId(1);
    currentUser.setEmail("blocker@example.com");
    currentUser.setUsername("blocker");
    currentUser.setRole(UserRole.USER);
    currentUser.setEmailVerified(true);
    when(jwtProvider.isTokenValid(VALID_TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(VALID_TOKEN)).thenReturn(currentUser.getEmail());
    when(userRepository.findByEmailIgnoreCase(currentUser.getEmail()))
        .thenReturn(Optional.of(currentUser));
  }

  private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request) {
    return request.header("Authorization", "Bearer " + VALID_TOKEN);
  }

  @Nested
  @DisplayName("POST /v1/api/blocks/{userId}")
  class BlockTests {

    @Test
    @DisplayName("shouldReturn204_whenTheBlockIsPlaced_happyPath")
    void shouldReturn204() throws Exception {
      // When / Then
      mockMvc.perform(authed(post(BLOCKS_URL + "/" + TARGET_ID))).andExpect(status().isNoContent());
      verify(blockService).block(currentUser.getId(), TARGET_ID);
    }

    @Test
    @DisplayName("shouldReturn204_whenTheUserIsAlreadyBlocked")
    void shouldBeIdempotent() throws Exception {
      // Given: the service treats a repeat block as a no-op rather than a conflict
      // When / Then
      mockMvc.perform(authed(post(BLOCKS_URL + "/" + TARGET_ID))).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("shouldReturn400_whenBlockingYourself")
    void shouldReturn400_whenSelfBlock() throws Exception {
      // Given
      doThrow(new ValidationException("You cannot block yourself"))
          .when(blockService)
          .block(currentUser.getId(), currentUser.getId());

      // When / Then
      mockMvc
          .perform(authed(post(BLOCKS_URL + "/" + currentUser.getId())))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn404_whenTheTargetUserDoesNotExist")
    void shouldReturn404() throws Exception {
      // Given
      doThrow(new NotFoundException("User not found with ID: " + TARGET_ID))
          .when(blockService)
          .block(currentUser.getId(), TARGET_ID);

      // When / Then
      mockMvc.perform(authed(post(BLOCKS_URL + "/" + TARGET_ID))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401() throws Exception {
      // When / Then
      mockMvc.perform(post(BLOCKS_URL + "/" + TARGET_ID)).andExpect(status().isUnauthorized());
    }
  }

  @Nested
  @DisplayName("DELETE /v1/api/blocks/{userId}")
  class UnblockTests {

    @Test
    @DisplayName("shouldReturn204_whenTheBlockIsLifted_happyPath")
    void shouldReturn204() throws Exception {
      // When / Then
      mockMvc
          .perform(authed(delete(BLOCKS_URL + "/" + TARGET_ID)))
          .andExpect(status().isNoContent());
      verify(blockService).unblock(currentUser.getId(), TARGET_ID);
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401() throws Exception {
      // When / Then
      mockMvc.perform(delete(BLOCKS_URL + "/" + TARGET_ID)).andExpect(status().isUnauthorized());
    }
  }

  @Nested
  @DisplayName("GET /v1/api/blocks")
  class GetBlockedUsersTests {

    @Test
    @DisplayName("shouldReturn200AndTheCallersOwnBlockList_happyPath")
    void shouldReturnList() throws Exception {
      // Given
      when(blockService.getBlockedUsers(currentUser.getId()))
          .thenReturn(
              List.of(
                  new PublicUserResponse(
                      TARGET_ID,
                      "blocked",
                      "Blocked User",
                      null,
                      0,
                      OffsetDateTime.parse("2026-01-01T00:00:00Z"))));

      // When / Then
      mockMvc
          .perform(authed(get(BLOCKS_URL)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].id").value(TARGET_ID))
          .andExpect(jsonPath("$[0].username").value("blocked"))
          .andExpect(jsonPath("$[0].email").doesNotExist());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401() throws Exception {
      // When / Then
      mockMvc.perform(get(BLOCKS_URL)).andExpect(status().isUnauthorized());
    }
  }
}
