package com.socialapp.chat.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

import com.socialapp.chat.dto.ChatTokenResponse;
import com.socialapp.chat.dto.GroupChatResponse;
import com.socialapp.chat.service.StreamChatService;
import com.socialapp.common.exception.ExternalApiException;
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
      when(streamChatService.issueToken(any()))
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
      when(streamChatService.issueToken(any()))
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

  // =====================================================================
  // POST /v1/api/chat/groups
  // =====================================================================

  /**
   * The two rejection statuses here are the point of the class. A member list that bean validation
   * can judge on its own — empty, over the ceiling, a blank name — is <b>422</b>; a list that only
   * turns out to be unusable once duplicates and the caller are removed is <b>400</b>, thrown by
   * the service. See {@code GlobalExceptionHandler}'s class Javadoc for why the two differ.
   */
  @Nested
  @DisplayName("POST /v1/api/chat/groups")
  class CreateGroupTests {

    private static final String BODY =
        "{\"name\":\"Nhom DATN\",\"memberIds\":[7,8],\"imageUrl\":null}";

    private MockHttpServletRequestBuilder createGroup(String json) {
      return authed(post(URL + "/groups").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    @DisplayName("shouldReturn201AndTheChannelHandle_happyPath")
    void shouldReturn201() throws Exception {
      when(streamChatService.createGroupChat(eq(CALLER_ID), eq("Nhom DATN"), isNull(), any()))
          .thenReturn(
              GroupChatResponse.builder()
                  .channelType("messaging")
                  .channelId("grp-abc")
                  .cid("messaging:grp-abc")
                  .name("Nhom DATN")
                  .memberIds(List.of("9001", "7", "8"))
                  .createdBy("9001")
                  .build());

      mockMvc
          .perform(createGroup(BODY))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.cid").value("messaging:grp-abc"))
          .andExpect(jsonPath("$.createdBy").value("9001"))
          .andExpect(jsonPath("$.memberIds[0]").value("9001"));
    }

    @Test
    @DisplayName("shouldPassTheAuthenticatedCallerAsOwner_notAnythingFromTheBody")
    void shouldTakeCallerFromThePrincipal() throws Exception {
      // A body naming its own owner must not be able to create a group somebody else owns, so the
      // request has no field for it at all — the id comes from the JWT.
      mockMvc.perform(
          createGroup("{\"name\":\"Nhom DATN\",\"memberIds\":[7,8],\"createdBy\":\"1\"}"));

      verify(streamChatService)
          .createGroupChat(eq(CALLER_ID), eq("Nhom DATN"), isNull(), eq(List.of(7, 8)));
    }

    @Test
    @DisplayName("shouldReturn422_whenTheMemberListIsEmpty")
    void shouldReturn422ForEmptyMembers() throws Exception {
      mockMvc
          .perform(createGroup("{\"name\":\"Nhom DATN\",\"memberIds\":[]}"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn422_whenTheGroupNameIsBlank")
    void shouldReturn422ForBlankName() throws Exception {
      mockMvc
          .perform(createGroup("{\"name\":\"   \",\"memberIds\":[7,8]}"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn422_whenTheMemberListIsOverStreamsChannelCeiling")
    void shouldReturn422ForTooManyMembers() throws Exception {
      // 100 others + the caller is 101, one past what a messaging channel holds. Rejected outright
      // rather than truncated: a group quietly missing people reads as a bug in the member picker.
      String tooMany =
          IntStream.rangeClosed(1, 100)
              .mapToObj(String::valueOf)
              .collect(Collectors.joining(",", "{\"name\":\"Big\",\"memberIds\":[", "]}"));

      mockMvc.perform(createGroup(tooMany)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn400_whenTheGroupIsTooSmallOnlyAfterDeduplication")
    void shouldReturn400ForTooSmallGroup() throws Exception {
      // [7, 7] satisfies @Size(min = 2) and is still one person, which only the service can see.
      when(streamChatService.createGroupChat(any(), any(), any(), any()))
          .thenThrow(
              new ValidationException("A group needs at least 2 other people besides yourself"));

      mockMvc
          .perform(createGroup("{\"name\":\"Nhom DATN\",\"memberIds\":[7,7]}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn400_whenABlockStandsBetweenTwoMembers")
    void shouldReturn400WhenBlocked() throws Exception {
      when(streamChatService.createGroupChat(any(), any(), any(), any()))
          .thenThrow(
              new ValidationException(
                  "Some of the people you selected cannot be in the same group"));

      mockMvc
          .perform(createGroup(BODY))
          .andExpect(status().isBadRequest())
          .andExpect(
              jsonPath("$.message").value(org.hamcrest.Matchers.not(containsString("block"))));
    }

    @Test
    @DisplayName("shouldReturn404_whenAMemberDoesNotExist")
    void shouldReturn404() throws Exception {
      when(streamChatService.createGroupChat(any(), any(), any(), any()))
          .thenThrow(new NotFoundException("User not found with ID: [8]"));

      mockMvc.perform(createGroup(BODY)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn503_whenStreamIsNotConfigured")
    void shouldReturn503WhenUnconfigured() throws Exception {
      when(streamChatService.createGroupChat(any(), any(), any(), any()))
          .thenThrow(new MissingConfigurationException("Stream Chat is not configured"));

      mockMvc.perform(createGroup(BODY)).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("shouldReturn503_whenStreamRefusesTheChannelCreate")
    void shouldReturn503WhenStreamFails() throws Exception {
      when(streamChatService.createGroupChat(any(), any(), any(), any()))
          .thenThrow(new ExternalApiException("Failed to create the group channel on Stream Chat"));

      mockMvc.perform(createGroup(BODY)).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      mockMvc
          .perform(post(URL + "/groups").contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isUnauthorized());
    }
  }
}
