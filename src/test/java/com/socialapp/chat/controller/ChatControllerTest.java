package com.socialapp.chat.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

import com.socialapp.chat.dto.ChatTokenResponse;
import com.socialapp.chat.service.StreamChatService;
import com.socialapp.common.exception.MissingConfigurationException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
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
 * System/API integration tests for {@link ChatController}, per ISTQB CTFL v4.0.1 Section 2.2.2,
 * using {@code @WebMvcTest} + {@code MockMvc}. {@link StreamChatService} is mocked.
 *
 * <p>{@code POST /chat/participants/{id}} is what opened chat past the friend circle: Stream
 * refuses to create a channel containing a user it has never seen, so somebody has to introduce an
 * arbitrary pair. The 503 case is not an edge case here — it is what a developer machine actually
 * returns, because {@code stream.chat.api-key/api-secret} are not set locally.
 */
@WebMvcTest(ChatController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class ChatControllerTest {

  private static final String URL = "/v1/api/chat";
  private static final String TOKEN = "a-valid-jwt-token";
  private static final Integer CALLER_ID = 9001;
  private static final Integer OTHER_ID = 42;

  @Autowired private MockMvc mockMvc;

  @MockBean private StreamChatService streamChatService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private BanDetailsService banDetailsService;
  @MockBean private UserRepository userRepository;

  @BeforeEach
  void setUpCaller() {
    UserEntity caller = new UserEntity();
    caller.setId(CALLER_ID);
    caller.setEmail("caller@example.com");
    caller.setUsername("caller");
    caller.setRole(UserRole.USER);
    caller.setEmailVerified(true);

    when(jwtProvider.isTokenValid(TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(TOKEN)).thenReturn(caller.getEmail());
    when(userRepository.findByEmailIgnoreCase(caller.getEmail())).thenReturn(Optional.of(caller));
  }

  private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
    return b.header("Authorization", "Bearer " + TOKEN);
  }

  // =====================================================================
  // GET /v1/api/chat/token
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/chat/token")
  class TokenTests {

    @Test
    @DisplayName("shouldReturn200AndTheApiKeyAlongsideTheToken_happyPath")
    void shouldReturnToken() throws Exception {
      when(streamChatService.issueToken(org.mockito.ArgumentMatchers.any()))
          .thenReturn(
              ChatTokenResponse.builder()
                  .userId(String.valueOf(CALLER_ID))
                  .apiKey("stream-public-key")
                  .streamToken("stream-jwt")
                  .expiresAt(Instant.parse("2026-12-31T00:00:00Z"))
                  .build());

      mockMvc
          .perform(authed(get(URL + "/token")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.apiKey").value("stream-public-key"))
          .andExpect(jsonPath("$.streamToken").value("stream-jwt"));
    }

    @Test
    @DisplayName("shouldReturn503_whenStreamIsNotConfigured")
    void shouldReturn503WhenUnconfigured() throws Exception {
      // The local-dev reality, and the reason C3 could not be measured end to end this session.
      when(streamChatService.issueToken(org.mockito.ArgumentMatchers.any()))
          .thenThrow(new MissingConfigurationException("Stream Chat is not configured"));

      mockMvc.perform(authed(get(URL + "/token"))).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      mockMvc.perform(get(URL + "/token")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // POST /v1/api/chat/participants/{userId}
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/chat/participants/{userId}")
  class EnsureParticipantsTests {

    @Test
    @DisplayName("shouldReturn204_happyPath")
    void shouldReturn204() throws Exception {
      mockMvc
          .perform(authed(post(URL + "/participants/" + OTHER_ID)))
          .andExpect(status().isNoContent());

      verify(streamChatService).ensureChatParticipants(CALLER_ID, OTHER_ID);
    }

    @Test
    @DisplayName("shouldReturn400_whenABlockStandsBetweenTheTwo")
    void shouldReturn400WhenBlocked() throws Exception {
      // The message deliberately does not confirm that a block is the reason — same rule as the
      // friend-request rejection.
      doThrow(new ValidationException("You cannot start a conversation with this user"))
          .when(streamChatService)
          .ensureChatParticipants(CALLER_ID, OTHER_ID);

      mockMvc
          .perform(authed(post(URL + "/participants/" + OTHER_ID)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("You cannot start a conversation with this user"));
    }

    @Test
    @DisplayName("shouldReturn400_whenTheTargetIsTheCallerThemselves")
    void shouldReturn400ForSelf() throws Exception {
      doThrow(new ValidationException("You cannot start a conversation with yourself"))
          .when(streamChatService)
          .ensureChatParticipants(CALLER_ID, CALLER_ID);

      mockMvc
          .perform(authed(post(URL + "/participants/" + CALLER_ID)))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn404_whenTheOtherUserDoesNotExist")
    void shouldReturn404() throws Exception {
      doThrow(new NotFoundException("User not found with ID: 777"))
          .when(streamChatService)
          .ensureChatParticipants(CALLER_ID, 777);

      mockMvc.perform(authed(post(URL + "/participants/777"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn503_whenStreamIsNotConfigured")
    void shouldReturn503WhenUnconfigured() throws Exception {
      doThrow(new MissingConfigurationException("Stream Chat is not configured"))
          .when(streamChatService)
          .ensureChatParticipants(CALLER_ID, OTHER_ID);

      mockMvc
          .perform(authed(post(URL + "/participants/" + OTHER_ID)))
          .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      mockMvc.perform(post(URL + "/participants/" + OTHER_ID)).andExpect(status().isUnauthorized());
    }
  }
}
