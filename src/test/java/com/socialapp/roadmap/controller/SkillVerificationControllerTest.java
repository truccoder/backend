package com.socialapp.roadmap.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.moderation.service.BanDetailsService;
import com.socialapp.roadmap.dto.PendingVerificationDto;
import com.socialapp.roadmap.dto.SkillVerificationRequestDto;
import com.socialapp.roadmap.enums.VerificationTier;
import com.socialapp.roadmap.service.SkillVerificationService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link SkillVerificationController}, per ISTQB CTFL v4.0.1
 * Section 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link SkillVerificationService} is
 * mocked.
 *
 * <p><b>The most valuable assertions here are the refusals.</b> Three of these four endpoints are
 * admin-only and none of them was enforced: {@code @PreAuthorize} was inert without {@code
 * @EnableMethodSecurity}, so any signed-in user could read {@code /skills/pending} — the whole
 * moderation queue, carrying every submitter's name, avatar and private {@code proofUrl} — and
 * could approve a claim. The exploit was one request long: file a {@code MOD_VERIFIED} claim
 * through {@code /skills/verify}, then approve your own {@code progressId} for the badge and the
 * reputation award. {@code approveRequest} checks the row's status and not who is asking, which is
 * correct for a moderator action and catastrophic when everyone is a moderator.
 *
 * <p>{@code POST /skills/verify} is the one endpoint that must stay open to a plain user, and the
 * test below pins that: over-tightening this surface would take the feature away from the people it
 * is for. It also pins that the claimant comes from the security context rather than the body.
 */
@WebMvcTest(SkillVerificationController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class SkillVerificationControllerTest {

  private static final String URL = "/v1/api/skills";
  private static final String ADMIN_TOKEN = "a-valid-admin-jwt-token";
  private static final String USER_TOKEN = "a-valid-user-jwt-token";
  private static final Integer USER_ID = 2;
  private static final Integer ADMIN_ID = 1;
  private static final Integer PROGRESS_ID = 42;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockBean private SkillVerificationService skillVerificationService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private UserRepository userRepository;

  /** {@code JwtAuthenticationFilter} builds the banned-account 403 through it. */
  @MockBean private BanDetailsService banDetailsService;

  @BeforeEach
  void setUpCallers() {
    register(sampleUser(ADMIN_ID, "admin@example.com", UserRole.ADMIN), ADMIN_TOKEN);
    register(sampleUser(USER_ID, "user@example.com", UserRole.USER), USER_TOKEN);
  }

  private void register(UserEntity user, String token) {
    when(jwtProvider.isTokenValid(token)).thenReturn(true);
    when(jwtProvider.extractEmail(token)).thenReturn(user.getEmail());
    when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
  }

  private static UserEntity sampleUser(Integer id, String email, UserRole role) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setEmail(email);
    user.setUsername("user" + id);
    user.setFullName("User " + id);
    user.setRole(role);
    user.setEmailVerified(true);
    return user;
  }

  private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + ADMIN_TOKEN);
  }

  private static MockHttpServletRequestBuilder asRegularUser(
      MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + USER_TOKEN);
  }

  private String json(Object body) throws Exception {
    return objectMapper.writeValueAsString(body);
  }

  private static SkillVerificationRequestDto verifyRequest() {
    SkillVerificationRequestDto dto = new SkillVerificationRequestDto();
    dto.setNodeId(11);
    dto.setTier(VerificationTier.MOD_VERIFIED);
    dto.setProofUrl("https://github.com/user2/proof");
    return dto;
  }

  // =====================================================================
  // POST /v1/api/skills/verify
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/skills/verify")
  class SubmitVerificationTests {

    @Test
    @DisplayName("shouldReturn200AndSubmit_whenCalledByAPlainUser_happyPath")
    void shouldAcceptRegularUser() throws Exception {
      // Claiming a skill is exactly what a plain user is meant to do — this must NOT be admin-only.
      mockMvc
          .perform(
              asRegularUser(post(URL + "/verify"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json(verifyRequest())))
          .andExpect(status().isOk());

      // The claimant comes from the security context, never from the payload: a body-supplied user
      // id would let anyone file claims in someone else's name.
      verify(skillVerificationService).submitVerificationRequest(eq(USER_ID), any());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldRejectGuest() throws Exception {
      mockMvc
          .perform(
              post(URL + "/verify")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json(verifyRequest())))
          .andExpect(status().isUnauthorized());

      verifyNoInteractions(skillVerificationService);
    }

    @Test
    @DisplayName("shouldReturn422_whenNodeIdIsMissing_boundary")
    void shouldRejectMissingNodeId() throws Exception {
      // 422 rather than 400: the body parsed, @NotNull refused it — the project's split, see
      // GlobalExceptionHandler's class javadoc.
      SkillVerificationRequestDto invalid = verifyRequest();
      invalid.setNodeId(null);

      mockMvc
          .perform(
              asRegularUser(post(URL + "/verify"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json(invalid)))
          .andExpect(status().isUnprocessableEntity());

      verifyNoInteractions(skillVerificationService);
    }
  }

  // =====================================================================
  // GET /v1/api/skills/pending
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/skills/pending")
  class PendingQueueTests {

    @Test
    @DisplayName("shouldReturn200AndTheQueue_whenCalledByAdmin_happyPath")
    void shouldReturnQueueForAdmin() throws Exception {
      PendingVerificationDto row =
          PendingVerificationDto.builder()
              .progressId(PROGRESS_ID)
              .userId(USER_ID)
              .username("user2")
              .fullName("User 2")
              .nodeId(11)
              .nodeName("Spring Boot")
              .tier(VerificationTier.MOD_VERIFIED)
              .proofUrl("https://github.com/user2/proof")
              .requestedAt(OffsetDateTime.parse("2026-08-01T00:00:00Z"))
              .build();
      when(skillVerificationService.getPendingRequests()).thenReturn(List.of(row));

      mockMvc
          .perform(asAdmin(get(URL + "/pending")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].progressId").value(PROGRESS_ID))
          .andExpect(jsonPath("$[0].proofUrl").value("https://github.com/user2/proof"));
    }

    @Test
    @DisplayName("shouldReturn403AndLeakNothing_whenCallerIsAPlainUser")
    void shouldRejectRegularUser() throws Exception {
      // The payload is the point: fullName, profilePictureUrl and proofUrl for every pending
      // claimant in the system. This returned 200 before @EnableMethodSecurity.
      mockMvc.perform(asRegularUser(get(URL + "/pending"))).andExpect(status().isForbidden());

      verifyNoInteractions(skillVerificationService);
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldRejectGuest() throws Exception {
      mockMvc.perform(get(URL + "/pending")).andExpect(status().isUnauthorized());

      verifyNoInteractions(skillVerificationService);
    }
  }

  // =====================================================================
  // POST /v1/api/skills/{progressId}/approve
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/skills/{progressId}/approve")
  class ApproveTests {

    private static final String APPROVE_URL = URL + "/" + PROGRESS_ID + "/approve";

    @Test
    @DisplayName("shouldReturn200AndRecordTheModerator_whenCalledByAdmin_happyPath")
    void shouldApproveAsAdmin() throws Exception {
      mockMvc.perform(asAdmin(post(APPROVE_URL))).andExpect(status().isOk());

      // The moderator recorded on the row is the caller, taken from the security context.
      verify(skillVerificationService).approveRequest(PROGRESS_ID, ADMIN_ID);
    }

    @Test
    @DisplayName("shouldReturn403AndNotApprove_whenCallerIsAPlainUser_selfApprovalExploit")
    void shouldRejectSelfApproval() throws Exception {
      // The whole verification trust model rests on this refusal: approveRequest checks only the
      // row's status, so a plain user reaching it could approve the claim they had just filed and
      // award themselves ROADMAP_NODE_VERIFIED reputation.
      mockMvc.perform(asRegularUser(post(APPROVE_URL))).andExpect(status().isForbidden());

      verifyNoInteractions(skillVerificationService);
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldRejectGuest() throws Exception {
      mockMvc.perform(post(APPROVE_URL)).andExpect(status().isUnauthorized());

      verifyNoInteractions(skillVerificationService);
    }
  }

  // =====================================================================
  // POST /v1/api/skills/{progressId}/reject
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/skills/{progressId}/reject")
  class RejectTests {

    private static final String REJECT_URL = URL + "/" + PROGRESS_ID + "/reject";

    @Test
    @DisplayName("shouldReturn200AndRecordTheModerator_whenCalledByAdmin_happyPath")
    void shouldRejectAsAdmin() throws Exception {
      mockMvc.perform(asAdmin(post(REJECT_URL))).andExpect(status().isOk());

      verify(skillVerificationService).rejectRequest(PROGRESS_ID, ADMIN_ID);
    }

    @Test
    @DisplayName("shouldReturn403AndNotReject_whenCallerIsAPlainUser")
    void shouldRejectRegularUser() throws Exception {
      // The mirror of self-approval: unguarded, any user could reject other people's claims.
      mockMvc.perform(asRegularUser(post(REJECT_URL))).andExpect(status().isForbidden());

      verifyNoInteractions(skillVerificationService);
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldRejectGuest() throws Exception {
      mockMvc.perform(post(REJECT_URL)).andExpect(status().isUnauthorized());

      verifyNoInteractions(skillVerificationService);
    }
  }
}
