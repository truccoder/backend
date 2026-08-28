package com.socialapp.notifications.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.socialapp.moderation.service.BanDetailsService;
import com.socialapp.notifications.dto.NotificationPreferenceResponseDto;
import com.socialapp.notifications.dto.NotificationResponseDto;
import com.socialapp.notifications.entity.enums.EmailFrequency;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.notifications.sse.NotificationStreamService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link NotificationController}, per ISTQB CTFL v4.0.1 Section
 * 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link NotificationService} is mocked.
 *
 * <p>Same two structural notes as {@code FriendshipController}/{@code PostController}: {@code
 * page}/{@code size} carry method-parameter {@code @Positive} constraints, validated as <b>400
 * Bad Request</b> (not the 422 used for {@code @RequestBody @Valid} failures — see {@code
 * GlobalExceptionHandler}'s class-level Javadoc for why that split is intentional); and {@code
 * updatePreferences}'s {@code @RequestBody} has neither {@code @Valid} nor any constraint
 * annotations on {@link com.socialapp.notifications.dto.UpdatePreferenceRequestDto}, so there is
 * no validation section for it. {@code markAsRead} also has no exception path to test: {@code
 * NotificationService#markAsRead} silently no-ops for a missing or not-owned notification id
 * rather than throwing, confirmed by reading the service.
 *
 * <p>Auth simulation follows the same real-{@link JwtAuthenticationFilter} pattern as the other
 * authenticated controllers in this suite.
 */
@WebMvcTest(NotificationController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class NotificationControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private NotificationService notificationService;
  @MockBean private NotificationStreamService notificationStreamService;
  @MockBean private JwtProvider jwtProvider;

  @MockBean
  private BanDetailsService
      banDetailsService; // JwtAuthenticationFilter builds the banned-account 403 through it

  @MockBean private UserRepository userRepository;

  private static final String NOTIFICATIONS_URL = "/v1/api/notifications";
  private static final String VALID_TOKEN = "a-valid-jwt-token";

  private UserEntity currentUser;

  @BeforeEach
  void setUpDefaultUser() {
    currentUser = new UserEntity();
    currentUser.setId(1);
    currentUser.setEmail("recipient@example.com");
    currentUser.setUsername("recipient");
    currentUser.setFullName("Recipient One");
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
  // GET /v1/api/notifications
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/notifications")
  class GetNotificationsTests {

    @Test
    @DisplayName("shouldReturn200AndPage_withDefaultPagination_happyPath")
    void shouldReturn200AndPage_withDefaultPagination_happyPath() throws Exception {
      // Given
      NotificationResponseDto notification =
          NotificationResponseDto.builder()
              .id(1)
              .type(NotificationType.POST_LIKED)
              .title("Someone liked your post")
              .isRead(false)
              .build();
      Page<NotificationResponseDto> page = new PageImpl<>(List.of(notification));
      when(notificationService.getNotifications(currentUser.getId(), 1, 10)).thenReturn(page);

      // When / Then
      mockMvc
          .perform(authed(get(NOTIFICATIONS_URL)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].id").value(1))
          .andExpect(jsonPath("$.content[0].title").value("Someone liked your post"))
          // A post like has no comment behind it, so the key is absent rather than null. This
          // project sets no global NON_NULL, so without the annotation on the field every friend
          // request and book purchase would carry a "postId": null that means nothing.
          .andExpect(jsonPath("$.content[0].postId").doesNotExist());
    }

    @Test
    @DisplayName("shouldExposePostId_whenNotificationIsAboutAComment")
    void shouldExposePostId_whenNotificationIsAboutAComment() throws Exception {
      // Given — the pair a client needs to navigate: referenceId says which reply, postId says
      // which page to open it on. Without the second one USER_MENTIONED and COMMENT_LIKED were
      // readable and un-tappable.
      NotificationResponseDto notification =
          NotificationResponseDto.builder()
              .id(2)
              .type(NotificationType.USER_MENTIONED)
              .title("You were mentioned in a comment")
              .referenceId(88)
              .referenceType("COMMENT")
              .postId(500)
              .isRead(false)
              .build();
      when(notificationService.getNotifications(currentUser.getId(), 1, 10))
          .thenReturn(new PageImpl<>(List.of(notification)));

      // When / Then
      mockMvc
          .perform(authed(get(NOTIFICATIONS_URL)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].referenceId").value(88))
          .andExpect(jsonPath("$.content[0].referenceType").value("COMMENT"))
          .andExpect(jsonPath("$.content[0].postId").value(500));
    }

    @Test
    @DisplayName("shouldPassPageAndSizeThrough_whenProvided")
    void shouldPassPageAndSizeThrough_whenProvided() throws Exception {
      // Given
      when(notificationService.getNotifications(currentUser.getId(), 2, 5))
          .thenReturn(new PageImpl<>(List.of()));

      // When / Then
      mockMvc
          .perform(authed(get(NOTIFICATIONS_URL)).param("page", "2").param("size", "5"))
          .andExpect(status().isOk());

      verify(notificationService).getNotifications(currentUser.getId(), 2, 5);
    }

    @Test
    @DisplayName("shouldReturn400_whenPageIsZero_boundary")
    void shouldReturn400_whenPageIsZero_boundary() throws Exception {
      // When / Then — BVA: @Positive requires > 0
      mockMvc
          .perform(authed(get(NOTIFICATIONS_URL)).param("page", "0"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn400_whenSizeIsZero_boundary")
    void shouldReturn400_whenSizeIsZero_boundary() throws Exception {
      // When / Then
      mockMvc
          .perform(authed(get(NOTIFICATIONS_URL)).param("size", "0"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(NOTIFICATIONS_URL)).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/notifications/unread-count
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/notifications/unread-count")
  class GetUnreadCountTests {

    @Test
    @DisplayName("shouldReturn200AndCount_happyPath")
    void shouldReturn200AndCount_happyPath() throws Exception {
      // Given
      when(notificationService.getUnreadCount(currentUser.getId())).thenReturn(3);

      // When / Then
      mockMvc
          .perform(authed(get(NOTIFICATIONS_URL + "/unread-count")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.count").value(3));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc
          .perform(get(NOTIFICATIONS_URL + "/unread-count"))
          .andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // POST /v1/api/notifications/{id}/read
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/notifications/{id}/read")
  class MarkAsReadTests {

    @Test
    @DisplayName("shouldReturn200_happyPath")
    void shouldReturn200_happyPath() throws Exception {
      // When / Then
      mockMvc.perform(authed(post(NOTIFICATIONS_URL + "/1/read"))).andExpect(status().isOk());

      verify(notificationService).markAsRead(currentUser.getId(), 1);
    }

    @Test
    @DisplayName("shouldReturn400_whenIdPathVariableIsNotANumber")
    void shouldReturn400_whenIdPathVariableIsNotANumber() throws Exception {
      // When / Then — EP: id must be an Integer
      mockMvc
          .perform(authed(post(NOTIFICATIONS_URL + "/not-a-number/read")))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(post(NOTIFICATIONS_URL + "/1/read")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // POST /v1/api/notifications/read-all
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/notifications/read-all")
  class MarkAllAsReadTests {

    @Test
    @DisplayName("shouldReturn200_happyPath")
    void shouldReturn200_happyPath() throws Exception {
      // When / Then
      mockMvc.perform(authed(post(NOTIFICATIONS_URL + "/read-all"))).andExpect(status().isOk());

      verify(notificationService).markAllAsRead(currentUser.getId());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(post(NOTIFICATIONS_URL + "/read-all")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/notifications/preferences
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/notifications/preferences")
  class GetPreferencesTests {

    @Test
    @DisplayName("shouldReturn200AndPreferences_happyPath")
    void shouldReturn200AndPreferences_happyPath() throws Exception {
      // Given
      when(notificationService.getPreference(currentUser.getId()))
          .thenReturn(
              NotificationPreferenceResponseDto.builder()
                  .userId(currentUser.getId())
                  .pushEnabled(true)
                  .emailFrequency(EmailFrequency.INSTANT)
                  .build());

      // When / Then
      mockMvc
          .perform(authed(get(NOTIFICATIONS_URL + "/preferences")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.pushEnabled").value(true))
          .andExpect(jsonPath("$.emailFrequency").value("INSTANT"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(NOTIFICATIONS_URL + "/preferences")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // PUT /v1/api/notifications/preferences
  // =====================================================================

  @Nested
  @DisplayName("PUT /v1/api/notifications/preferences")
  class UpdatePreferencesTests {

    @Test
    @DisplayName("shouldReturn200AndUpdatedPreferences_happyPath")
    void shouldReturn200AndUpdatedPreferences_happyPath() throws Exception {
      // Given
      when(notificationService.updatePreference(eq(currentUser.getId()), any()))
          .thenReturn(
              NotificationPreferenceResponseDto.builder()
                  .userId(currentUser.getId())
                  .pushEnabled(false)
                  .emailFrequency(EmailFrequency.NONE)
                  .build());
      String requestJson =
          """
          { "pushEnabled": false, "emailFrequency": "NONE" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(put(NOTIFICATIONS_URL + "/preferences"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.pushEnabled").value(false))
          .andExpect(jsonPath("$.emailFrequency").value("NONE"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // Given
      String requestJson =
          """
          { "pushEnabled": false }
          """;

      // When / Then
      mockMvc
          .perform(
              put(NOTIFICATIONS_URL + "/preferences")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isUnauthorized());
    }
  }

  @Nested
  @DisplayName("GET /v1/api/notifications/stream")
  class StreamTests {

    @Test
    @DisplayName("shouldOpenAnEventStreamForTheCaller_happyPath")
    void shouldOpenStream() throws Exception {
      // Given
      when(notificationStreamService.subscribe(currentUser.getId()))
          .thenReturn(new SseEmitter(30_000L));

      // When / Then — the response is held open rather than completed, which is the whole point:
      // this is what replaces the bell's polling loop
      mockMvc
          .perform(
              get(NOTIFICATIONS_URL + "/stream")
                  .header("Authorization", "Bearer " + VALID_TOKEN)
                  .accept(MediaType.TEXT_EVENT_STREAM))
          .andExpect(status().isOk())
          .andExpect(request().asyncStarted());
    }

    @Test
    @DisplayName("shouldSubscribeTheCallerFromTheSecurityContext_notAPathOrParameter")
    void shouldSubscribeTheAuthenticatedCaller() throws Exception {
      // Given
      when(notificationStreamService.subscribe(currentUser.getId()))
          .thenReturn(new SseEmitter(30_000L));

      // When
      mockMvc
          .perform(
              get(NOTIFICATIONS_URL + "/stream")
                  .header("Authorization", "Bearer " + VALID_TOKEN)
                  .accept(MediaType.TEXT_EVENT_STREAM))
          .andExpect(status().isOk());

      // Then — a user id taken from the request would let anyone read anyone's notifications
      verify(notificationStreamService).subscribe(currentUser.getId());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenUnauthenticated() throws Exception {
      // When / Then
      mockMvc
          .perform(get(NOTIFICATIONS_URL + "/stream").accept(MediaType.TEXT_EVENT_STREAM))
          .andExpect(status().isUnauthorized());

      verify(notificationStreamService, never()).subscribe(any());
    }

    @Test
    @DisplayName("shouldStillServeAClientThatAsksForJson_becauseProducesIsPinned")
    void shouldIgnoreAcceptHeader() throws Exception {
      // Given — content negotiation on a handler returning SseEmitter otherwise depends on what
      // the client sent, and a client asking for JSON would get a 406 from an endpoint that
      // plainly is not JSON
      when(notificationStreamService.subscribe(currentUser.getId()))
          .thenReturn(new SseEmitter(30_000L));

      // When / Then
      mockMvc
          .perform(
              get(NOTIFICATIONS_URL + "/stream")
                  .header("Authorization", "Bearer " + VALID_TOKEN)
                  .accept(MediaType.ALL))
          .andExpect(status().isOk());
    }
  }
}
