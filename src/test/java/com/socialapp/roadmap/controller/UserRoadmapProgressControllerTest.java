package com.socialapp.roadmap.controller;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
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

import com.socialapp.moderation.service.BanDetailsService;
import com.socialapp.roadmap.dto.RoadmapProgressDto;
import com.socialapp.roadmap.enums.VerificationStatus;
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
 * System/API integration tests for {@link UserRoadmapProgressController}, per ISTQB CTFL v4.0.1
 * Section 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}.
 *
 * <p>This endpoint is one of the few open to signed-out visitors, so the guest case is a real
 * assertion rather than a 401 check: {@code SecurityConfig} must permit it <b>and</b> the
 * controller must read the viewer with the non-throwing {@code getCurrentUserIdOrNull()}, because
 * the throwing variant 401s a guest no matter what the security config allows. Both halves are
 * exercised here against the real filter chain.
 */
@WebMvcTest(UserRoadmapProgressController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class UserRoadmapProgressControllerTest {

  private static final String TOKEN = "a-valid-jwt-token";
  private static final Integer VIEWER_ID = 5;
  private static final Integer PROFILE_ID = 9001;

  @Autowired private MockMvc mockMvc;

  @MockBean private SkillVerificationService skillVerificationService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private BanDetailsService banDetailsService;
  @MockBean private UserRepository userRepository;

  @BeforeEach
  void setUpCaller() {
    UserEntity viewer = new UserEntity();
    viewer.setId(VIEWER_ID);
    viewer.setEmail("viewer@example.com");
    viewer.setUsername("viewer");
    viewer.setRole(UserRole.USER);
    viewer.setEmailVerified(true);

    when(jwtProvider.isTokenValid(TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(TOKEN)).thenReturn(viewer.getEmail());
    when(userRepository.findByEmailIgnoreCase(viewer.getEmail())).thenReturn(Optional.of(viewer));
  }

  private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
    return b.header("Authorization", "Bearer " + TOKEN);
  }

  private static String url(Integer userId) {
    return "/v1/api/users/" + userId + "/roadmap-progress";
  }

  private static RoadmapProgressDto verified() {
    return RoadmapProgressDto.builder()
        .nodeId(1)
        .nodeName("Java")
        .tier(VerificationTier.MOD_VERIFIED)
        .status(VerificationStatus.VERIFIED)
        .verifiedAt(OffsetDateTime.parse("2026-08-01T00:00:00Z"))
        .build();
  }

  @Nested
  @DisplayName("GET /v1/api/users/{userId}/roadmap-progress")
  class GetProgressTests {

    @Test
    @DisplayName("shouldReturn200AndTheVerifiedSkills_whenCalledBySignedInViewer_happyPath")
    void shouldReturnForSignedInViewer() throws Exception {
      when(skillVerificationService.getProgressForUser(PROFILE_ID, VIEWER_ID))
          .thenReturn(List.of(verified()));

      mockMvc
          .perform(authed(get(url(PROFILE_ID))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].nodeName").value("Java"))
          .andExpect(jsonPath("$[0].status").value("VERIFIED"));
    }

    @Test
    @DisplayName("shouldReturn200_whenCalledByAGuestWithNoAuthorizationHeader")
    void shouldReturn200ForGuest() throws Exception {
      // The card belongs to a public profile, so a guest must get through — and the viewer id
      // reaches the service as null rather than 401-ing on the way in.
      when(skillVerificationService.getProgressForUser(PROFILE_ID, null))
          .thenReturn(List.of(verified()));

      mockMvc
          .perform(get(url(PROFILE_ID)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].nodeName").value("Java"));

      verify(skillVerificationService).getProgressForUser(PROFILE_ID, null);
    }

    @Test
    @DisplayName("shouldNeverExposeProofLinksOrTheVerifier")
    void shouldNotExposeProof() throws Exception {
      // G1a: the proof is a personal link submitted to a moderator, not something to publish, and
      // which moderator approved a skill is internal. Neither has a field on the DTO — asserted
      // here so re-adding one to RoadmapProgressDto breaks a test instead of leaking quietly.
      when(skillVerificationService.getProgressForUser(PROFILE_ID, null))
          .thenReturn(List.of(verified()));

      mockMvc
          .perform(get(url(PROFILE_ID)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].proofUrl").doesNotExist())
          .andExpect(jsonPath("$[0].proofImageKey").doesNotExist())
          .andExpect(jsonPath("$[0].verifier").doesNotExist());
    }

    @Test
    @DisplayName("shouldPassTheViewerIdThrough_soTheOwnerSeesMoreThanAStranger")
    void shouldPassViewerIdThrough() throws Exception {
      // The owner/stranger split is the service's call; the controller's job is only to hand over
      // who is asking. Getting this wrong would show pending and rejected claims to everyone.
      when(skillVerificationService.getProgressForUser(VIEWER_ID, VIEWER_ID)).thenReturn(List.of());

      mockMvc.perform(authed(get(url(VIEWER_ID)))).andExpect(status().isOk());

      verify(skillVerificationService).getProgressForUser(VIEWER_ID, VIEWER_ID);
    }

    @Test
    @DisplayName("shouldReturn200AndEmptyList_whenTheUserHasNoProgress")
    void shouldReturnEmptyList() throws Exception {
      when(skillVerificationService.getProgressForUser(eqProfile(), isNull()))
          .thenReturn(List.of());

      mockMvc
          .perform(get(url(PROFILE_ID)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$").isArray())
          .andExpect(jsonPath("$").isEmpty());
    }

    private Integer eqProfile() {
      return org.mockito.ArgumentMatchers.eq(PROFILE_ID);
    }

    @Test
    @DisplayName("shouldReturn400_whenUserIdIsNotANumber")
    void shouldReturn400ForNonNumericId() throws Exception {
      mockMvc
          .perform(get("/v1/api/users/not-a-number/roadmap-progress"))
          .andExpect(status().isBadRequest());
    }
  }
}
