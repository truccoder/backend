package com.socialapp.friendships.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.friendships.dto.FriendListResponseDto;
import com.socialapp.friendships.dto.FriendRequestPageResponseDto;
import com.socialapp.friendships.dto.FriendSuggestionDto;
import com.socialapp.friendships.dto.PendingFriendRequestDto;
import com.socialapp.friendships.dto.SentFriendRequestDto;
import com.socialapp.friendships.dto.SentFriendRequestPageResponseDto;
import com.socialapp.friendships.dto.UserProfileDto;
import com.socialapp.friendships.entity.enums.FriendRequestStatus;
import com.socialapp.friendships.service.FriendshipService;
import com.socialapp.knowledge.entity.enums.PrimaryRole;
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
 * System/API integration tests for {@link FriendshipController}, per ISTQB CTFL v4.0.1 Section
 * 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link FriendshipService} is mocked.
 *
 * <p><b>Validation shape differs from body-based controllers.</b> Every endpoint here takes
 * {@code @RequestParam}/{@code @PathVariable} inputs, never {@code @RequestBody}. The {@code
 * limit} query params carry method-parameter-level {@code @Positive} constraints (not {@code
 * @Valid} on a DTO), which Spring validates via {@code HandlerMethodValidationException} —
 * handled by {@code GlobalExceptionHandler#handle(HandlerMethodValidationException, ...)} as
 * <b>400 Bad Request</b>, not the 422 seen for {@code @RequestBody @Valid} failures on
 * AuthController/ProfileController. Confirmed by reading both the controller and the handler.
 *
 * <p><b>Auth simulation:</b> same real-{@link JwtAuthenticationFilter} approach as the other
 * authenticated controllers in this suite — {@code SecurityUtils.getCurrentUserId()} requires a
 * {@link UserEntity} principal, which {@code @WithMockUser} does not provide.
 */
@WebMvcTest(FriendshipController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class FriendshipControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private FriendshipService friendshipService;
  @MockBean private JwtProvider jwtProvider;

  @MockBean
  private BanDetailsService
      banDetailsService; // JwtAuthenticationFilter builds the banned-account 403 through it

  @MockBean private UserRepository userRepository;

  private static final String FRIENDSHIPS_URL = "/v1/api/friendships";
  private static final String SUGGESTIONS_URL = "/v1/api/friendships/suggestions";
  private static final String VALID_TOKEN = "a-valid-jwt-token";

  private UserEntity currentUser;

  @BeforeEach
  void setUpDefaultUser() {
    currentUser = new UserEntity();
    currentUser.setId(1);
    currentUser.setEmail("user@example.com");
    currentUser.setUsername("user1");
    currentUser.setFullName("User One");
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
  // GET /v1/api/friendships
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/friendships")
  class GetFriendsTests {

    @Test
    @DisplayName("shouldReturn200AndFriendList_whenCalledByAnAuthenticatedUser_happyPath")
    void shouldReturn200AndFriendList_whenCalledByAnAuthenticatedUser_happyPath() throws Exception {
      // Given
      FriendListResponseDto response =
          new FriendListResponseDto(
              List.of(new UserProfileDto(2, "friend1", "Friend One", null)), 2, false, 1L);
      when(friendshipService.getFriends(eq(currentUser.getId()), isNull(), eq(20)))
          .thenReturn(response);

      // When / Then
      mockMvc
          .perform(authed(get(FRIENDSHIPS_URL)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.friends[0].userId").value(2))
          .andExpect(jsonPath("$.friends[0].username").value("friend1"))
          .andExpect(jsonPath("$.hasMore").value(false))
          .andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    @DisplayName("shouldPassCursorThrough_whenCursorIsProvided")
    void shouldPassCursorThrough_whenCursorIsProvided() throws Exception {
      // Given
      when(friendshipService.getFriends(eq(currentUser.getId()), eq(5), eq(10)))
          .thenReturn(new FriendListResponseDto(List.of(), null, false, 0L));

      // When / Then
      mockMvc
          .perform(authed(get(FRIENDSHIPS_URL)).param("cursor", "5").param("limit", "10"))
          .andExpect(status().isOk());

      verify(friendshipService).getFriends(currentUser.getId(), 5, 10);
    }

    @Test
    @DisplayName("shouldReturn400_whenLimitIsZero_boundary")
    void shouldReturn400_whenLimitIsZero_boundary() throws Exception {
      // When / Then — BVA: @Positive requires > 0, 0 is the invalid boundary
      mockMvc
          .perform(authed(get(FRIENDSHIPS_URL)).param("limit", "0"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn400_whenLimitIsNegative")
    void shouldReturn400_whenLimitIsNegative() throws Exception {
      // When / Then — EP: negative numbers are outside the @Positive partition
      mockMvc
          .perform(authed(get(FRIENDSHIPS_URL)).param("limit", "-1"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn200_whenLimitIsOne_boundary")
    void shouldReturn200_whenLimitIsOne_boundary() throws Exception {
      // Given — BVA: 1 is the smallest valid value for @Positive
      when(friendshipService.getFriends(eq(currentUser.getId()), isNull(), eq(1)))
          .thenReturn(new FriendListResponseDto(List.of(), null, false, 0L));

      // When / Then
      mockMvc.perform(authed(get(FRIENDSHIPS_URL)).param("limit", "1")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(FRIENDSHIPS_URL)).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/friendships/suggestions
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/friendships/suggestions")
  class GetSuggestionsTests {

    @Test
    @DisplayName("shouldReturn200AndSuggestions_happyPath")
    void shouldReturn200AndSuggestions_happyPath() throws Exception {
      // Given
      when(friendshipService.getSuggestions(currentUser.getId(), 10))
          .thenReturn(
              List.of(
                  new FriendSuggestionDto(
                      new UserProfileDto(3, "suggested", "Sug Gested", null),
                      4L,
                      PrimaryRole.BACKEND,
                      List.of("Java"),
                      List.of("java"))));

      // When / Then
      mockMvc
          .perform(authed(get(SUGGESTIONS_URL)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].profile.userId").value(3))
          .andExpect(jsonPath("$[0].mutualFriends").value(4))
          .andExpect(jsonPath("$[0].sharedRole").value("BACKEND"))
          .andExpect(jsonPath("$[0].matchedSkills[0]").value("Java"))
          .andExpect(jsonPath("$[0].sharedHashtags[0]").value("java"));
    }

    @Test
    @DisplayName("shouldReturn400_whenLimitIsZero_boundary")
    void shouldReturn400_whenLimitIsZero_boundary() throws Exception {
      // When / Then
      mockMvc
          .perform(authed(get(SUGGESTIONS_URL)).param("limit", "0"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(SUGGESTIONS_URL)).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/friendships/requests/pending
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/friendships/requests/pending")
  class GetPendingRequestsTests {

    @Test
    @DisplayName("shouldReturn200AndIncomingRequests_whenCalledByAnAuthenticatedUser_happyPath")
    void shouldReturn200AndIncomingRequests_whenCalledByAnAuthenticatedUser_happyPath()
        throws Exception {
      // Given
      PendingFriendRequestDto request =
          new PendingFriendRequestDto(
              5,
              2,
              "Friend Two",
              "http://cdn.example.com/avatar2.png",
              FriendRequestStatus.PENDING,
              OffsetDateTime.parse("2026-01-01T00:00:00Z"));
      when(friendshipService.getPendingRequests(eq(currentUser.getId()), isNull(), anyInt()))
          .thenReturn(new FriendRequestPageResponseDto(List.of(request), null, false));

      // When / Then
      mockMvc
          .perform(authed(get(FRIENDSHIPS_URL + "/requests/pending")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.requests[0].id").value(5))
          .andExpect(jsonPath("$.requests[0].requesterId").value(2))
          .andExpect(jsonPath("$.requests[0].requesterFullName").value("Friend Two"))
          .andExpect(jsonPath("$.requests[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("shouldReturn200AndEmptyList_whenUserHasNoIncomingRequests")
    void shouldReturn200AndEmptyList_whenUserHasNoIncomingRequests() throws Exception {
      // Given
      when(friendshipService.getPendingRequests(eq(currentUser.getId()), isNull(), anyInt()))
          .thenReturn(new FriendRequestPageResponseDto(List.of(), null, false));

      // When / Then
      mockMvc
          .perform(authed(get(FRIENDSHIPS_URL + "/requests/pending")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.requests").isEmpty())
          .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc
          .perform(get(FRIENDSHIPS_URL + "/requests/pending"))
          .andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/friendships/requests/sent
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/friendships/requests/sent")
  class GetSentRequestsTests {

    @Test
    @DisplayName("shouldReturn200AndOutgoingRequests_whenCalledByAnAuthenticatedUser_happyPath")
    void shouldReturn200AndOutgoingRequests_whenCalledByAnAuthenticatedUser_happyPath()
        throws Exception {
      // Given
      SentFriendRequestDto request =
          new SentFriendRequestDto(
              7,
              3,
              "Friend Three",
              null,
              FriendRequestStatus.PENDING,
              OffsetDateTime.parse("2026-01-02T00:00:00Z"));
      when(friendshipService.getSentRequests(eq(currentUser.getId()), isNull(), anyInt()))
          .thenReturn(new SentFriendRequestPageResponseDto(List.of(request), null, false));

      // When / Then
      mockMvc
          .perform(authed(get(FRIENDSHIPS_URL + "/requests/sent")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.requests[0].id").value(7))
          .andExpect(jsonPath("$.requests[0].addresseeId").value(3))
          .andExpect(jsonPath("$.requests[0].addresseeFullName").value("Friend Three"))
          .andExpect(jsonPath("$.requests[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(FRIENDSHIPS_URL + "/requests/sent")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // POST /v1/api/friendships/requests/{addresseeId}
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/friendships/requests/{addresseeId}")
  class SendFriendRequestTests {

    @Test
    @DisplayName("shouldReturn200_whenAddresseeIsValid_happyPath")
    void shouldReturn200_whenAddresseeIsValid_happyPath() throws Exception {
      // When / Then
      mockMvc.perform(authed(post(FRIENDSHIPS_URL + "/requests/2"))).andExpect(status().isOk());

      verify(friendshipService).sendFriendRequest(currentUser.getId(), 2);
    }

    @Test
    @DisplayName("shouldReturn400_whenSendingRequestToSelf")
    void shouldReturn400_whenSendingRequestToSelf() throws Exception {
      // Given
      doThrow(new ValidationException("You cannot send a friend request to yourself"))
          .when(friendshipService)
          .sendFriendRequest(anyInt(), anyInt());

      // When / Then
      mockMvc
          .perform(authed(post(FRIENDSHIPS_URL + "/requests/1")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("You cannot send a friend request to yourself"));
    }

    @Test
    @DisplayName("shouldReturn404_whenAddresseeDoesNotExist")
    void shouldReturn404_whenAddresseeDoesNotExist() throws Exception {
      // Given
      doThrow(new NotFoundException("User not found with ID: 999"))
          .when(friendshipService)
          .sendFriendRequest(anyInt(), anyInt());

      // When / Then
      mockMvc
          .perform(authed(post(FRIENDSHIPS_URL + "/requests/999")))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value("User not found with ID: 999"));
    }

    @Test
    @DisplayName("shouldReturn400_whenPendingRequestAlreadyExists")
    void shouldReturn400_whenPendingRequestAlreadyExists() throws Exception {
      // Given
      doThrow(
              new ValidationException(
                  "A pending friend request already exists between these users"))
          .when(friendshipService)
          .sendFriendRequest(anyInt(), anyInt());

      // When / Then
      mockMvc
          .perform(authed(post(FRIENDSHIPS_URL + "/requests/2")))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn400_whenAddresseeIdPathVariableIsNotANumber")
    void shouldReturn400_whenAddresseeIdPathVariableIsNotANumber() throws Exception {
      // When / Then — EP: addresseeId must be an Integer
      mockMvc
          .perform(authed(post(FRIENDSHIPS_URL + "/requests/not-a-number")))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(post(FRIENDSHIPS_URL + "/requests/2")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // DELETE /v1/api/friendships/requests/{requestId}
  // =====================================================================

  @Nested
  @DisplayName("DELETE /v1/api/friendships/requests/{requestId}")
  class CancelFriendRequestTests {

    @Test
    @DisplayName("shouldReturn200_whenCallerIsTheRequester_happyPath")
    void shouldReturn200_whenCallerIsTheRequester_happyPath() throws Exception {
      // When / Then
      mockMvc.perform(authed(delete(FRIENDSHIPS_URL + "/requests/10"))).andExpect(status().isOk());

      verify(friendshipService).cancelFriendRequest(currentUser.getId(), 10);
    }

    @Test
    @DisplayName("shouldReturn403_whenCallerIsNotTheRequester")
    void shouldReturn403_whenCallerIsNotTheRequester() throws Exception {
      // Given
      doThrow(new ForbiddenException("Only the requester can cancel this friend request"))
          .when(friendshipService)
          .cancelFriendRequest(anyInt(), anyInt());

      // When / Then
      mockMvc
          .perform(authed(delete(FRIENDSHIPS_URL + "/requests/10")))
          .andExpect(status().isForbidden())
          .andExpect(
              jsonPath("$.message").value("Only the requester can cancel this friend request"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc
          .perform(delete(FRIENDSHIPS_URL + "/requests/10"))
          .andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // POST /v1/api/friendships/requests/{requestId}/accept
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/friendships/requests/{requestId}/accept")
  class AcceptFriendRequestTests {

    @Test
    @DisplayName("shouldReturn200_whenCallerIsTheAddressee_happyPath")
    void shouldReturn200_whenCallerIsTheAddressee_happyPath() throws Exception {
      // When / Then
      mockMvc
          .perform(authed(post(FRIENDSHIPS_URL + "/requests/10/accept")))
          .andExpect(status().isOk());

      verify(friendshipService).acceptFriendRequest(currentUser.getId(), 10);
    }

    @Test
    @DisplayName("shouldReturn403_whenCallerIsNotTheAddressee")
    void shouldReturn403_whenCallerIsNotTheAddressee() throws Exception {
      // Given
      doThrow(new ForbiddenException("Only the addressee can accept this friend request"))
          .when(friendshipService)
          .acceptFriendRequest(anyInt(), anyInt());

      // When / Then
      mockMvc
          .perform(authed(post(FRIENDSHIPS_URL + "/requests/10/accept")))
          .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc
          .perform(post(FRIENDSHIPS_URL + "/requests/10/accept"))
          .andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // POST /v1/api/friendships/requests/{requestId}/reject
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/friendships/requests/{requestId}/reject")
  class RejectFriendRequestTests {

    @Test
    @DisplayName("shouldReturn200_whenCallerIsTheAddressee_happyPath")
    void shouldReturn200_whenCallerIsTheAddressee_happyPath() throws Exception {
      // When / Then
      mockMvc
          .perform(authed(post(FRIENDSHIPS_URL + "/requests/10/reject")))
          .andExpect(status().isOk());

      verify(friendshipService).rejectFriendRequest(currentUser.getId(), 10);
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc
          .perform(post(FRIENDSHIPS_URL + "/requests/10/reject"))
          .andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // DELETE /v1/api/friendships/{userId}
  // =====================================================================

  @Nested
  @DisplayName("DELETE /v1/api/friendships/{userId}")
  class UnfriendTests {

    @Test
    @DisplayName("shouldReturn204AndUnfriend_happyPath")
    void shouldReturn204() throws Exception {
      // When / Then
      mockMvc.perform(authed(delete(FRIENDSHIPS_URL + "/7"))).andExpect(status().isNoContent());
      verify(friendshipService).unfriend(currentUser.getId(), 7);
    }

    @Test
    @DisplayName("shouldReturn204_whenTheTwoWereNotFriends")
    void shouldBeIdempotent() throws Exception {
      // Given: the service treats this as a no-op — the caller asked for a state already true
      // When / Then
      mockMvc.perform(authed(delete(FRIENDSHIPS_URL + "/7"))).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("shouldReturn400_whenUnfriendingYourself")
    void shouldReturn400ForSelf() throws Exception {
      // Given
      doThrow(new ValidationException("You cannot unfriend yourself"))
          .when(friendshipService)
          .unfriend(currentUser.getId(), currentUser.getId());

      // When / Then
      mockMvc
          .perform(authed(delete(FRIENDSHIPS_URL + "/" + currentUser.getId())))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401() throws Exception {
      // When / Then
      mockMvc.perform(delete(FRIENDSHIPS_URL + "/7")).andExpect(status().isUnauthorized());
    }
  }
}
