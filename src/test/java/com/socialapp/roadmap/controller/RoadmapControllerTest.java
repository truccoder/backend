package com.socialapp.roadmap.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.common.enums.LearningCategory;
import com.socialapp.moderation.service.BanDetailsService;
import com.socialapp.roadmap.dto.RoadmapDto;
import com.socialapp.roadmap.dto.RoadmapNodeDto;
import com.socialapp.roadmap.service.RoadmapService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link RoadmapController}, per ISTQB CTFL v4.0.1 Section 2.2.2,
 * using {@code @WebMvcTest} + {@code MockMvc}. {@link RoadmapService} is mocked.
 *
 * <p><b>This class exists because of a hole, not for coverage.</b> The two write endpoints carry
 * {@code @PreAuthorize("hasRole('ADMIN')")} and enforced nothing at all: {@code
 * @EnableMethodSecurity} was absent from {@link SecurityConfig}, so any signed-in user could create
 * roadmaps and nodes. Nothing failed — not startup, not a test, because there was no test. The 403
 * cases below are the regression guard; if the switch is ever dropped again they go red.
 *
 * <p>The denial is asserted through the real filter chain, so it passes whether it comes from the
 * annotation or from the duplicate path rule in {@code SecurityConfig}. That is intentional: what
 * matters to a caller is that the request is refused, and the two layers are meant to be
 * interchangeable. Each denial also asserts the service was never reached — a 403 rendered *after*
 * the write would be worthless.
 */
@WebMvcTest(RoadmapController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class RoadmapControllerTest {

  private static final String URL = "/v1/api/roadmaps";
  private static final String ADMIN_TOKEN = "a-valid-admin-jwt-token";
  private static final String USER_TOKEN = "a-valid-user-jwt-token";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockBean private RoadmapService roadmapService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private UserRepository userRepository;

  /** {@code JwtAuthenticationFilter} builds the banned-account 403 through it. */
  @MockBean private BanDetailsService banDetailsService;

  @BeforeEach
  void setUpCallers() {
    register(sampleUser(1, "admin@example.com", UserRole.ADMIN), ADMIN_TOKEN);
    register(sampleUser(2, "user@example.com", UserRole.USER), USER_TOKEN);
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

  private static RoadmapDto roadmapRequest() {
    RoadmapDto dto = new RoadmapDto();
    dto.setName("Backend");
    dto.setDescription("Java track");
    dto.setCategory(LearningCategory.BACKEND);
    return dto;
  }

  private static RoadmapNodeDto nodeRequest() {
    RoadmapNodeDto dto = new RoadmapNodeDto();
    dto.setName("Spring Boot");
    dto.setOrderIndex(1);
    return dto;
  }

  // =====================================================================
  // POST /v1/api/roadmaps
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/roadmaps")
  class CreateRoadmapTests {

    @Test
    @DisplayName("shouldReturn200AndTheRoadmap_whenCalledByAdmin_happyPath")
    void shouldCreateForAdmin() throws Exception {
      RoadmapDto created = roadmapRequest();
      created.setId(7);
      when(roadmapService.createRoadmap(any())).thenReturn(created);

      mockMvc
          .perform(
              asAdmin(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json(roadmapRequest())))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(7))
          .andExpect(jsonPath("$.name").value("Backend"))
          .andExpect(jsonPath("$.category").value("BACKEND"));
    }

    @Test
    @DisplayName("shouldReturn400_whenCategoryIsNotAValidEnumValue")
    void shouldRejectUnknownCategory() throws Exception {
      // EP: category phải là một hằng số của LearningCategory. Nhận bừa rồi lặng lẽ lưu OTHER sẽ
      // tạo ra một lộ trình nằm sai tab mà không ai được báo.
      mockMvc
          .perform(
              asAdmin(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"name\":\"Backend\",\"category\":\"KHONG_CO_THAT\"}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn403AndNotCreate_whenCallerIsAPlainUser")
    void shouldRejectRegularUser() throws Exception {
      // B20: this measured 200 before @EnableMethodSecurity — anyone with an account could author
      // the catalogue every other user reads.
      mockMvc
          .perform(
              asRegularUser(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json(roadmapRequest())))
          .andExpect(status().isForbidden());

      verifyNoInteractions(roadmapService);
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldRejectGuest() throws Exception {
      mockMvc
          .perform(
              post(URL).contentType(MediaType.APPLICATION_JSON).content(json(roadmapRequest())))
          .andExpect(status().isUnauthorized());

      verifyNoInteractions(roadmapService);
    }

    @Test
    @DisplayName("shouldReturn422_whenNameIsBlank_boundary")
    void shouldRejectBlankName() throws Exception {
      // Authorisation passes first, so this proves the admin path reaches @Valid rather than being
      // refused for the wrong reason. 422, not 400 — a @RequestBody @Valid failure is a well-formed
      // request the server understood and refused; see GlobalExceptionHandler's class javadoc.
      RoadmapDto invalid = roadmapRequest();
      invalid.setName("  ");

      mockMvc
          .perform(
              asAdmin(post(URL)).contentType(MediaType.APPLICATION_JSON).content(json(invalid)))
          .andExpect(status().isUnprocessableEntity());

      verify(roadmapService, never()).createRoadmap(any());
    }
  }

  // =====================================================================
  // GET /v1/api/roadmaps
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/roadmaps")
  class GetAllRoadmapsTests {

    @Test
    @DisplayName("shouldReturn200AndTheCatalogue_whenCalledByAPlainUser_happyPath")
    void shouldReadAsRegularUser() throws Exception {
      // Reading is deliberately NOT admin-gated — every user browses the catalogue. Guarding this
      // by mistake would empty the roadmap page for everyone.
      RoadmapDto dto = roadmapRequest();
      dto.setId(7);
      when(roadmapService.getAllRoadmaps()).thenReturn(List.of(dto));

      mockMvc
          .perform(asRegularUser(get(URL)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].name").value("Backend"))
          // FE dựng tab từ chính danh sách này, nên thiếu trường là hỏng cả màn hình phân loại.
          .andExpect(jsonPath("$[0].category").value("BACKEND"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledByAGuest")
    void shouldRejectGuest() throws Exception {
      // The catalogue is not part of the guest-readable surface in SecurityConfig.
      mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // POST /v1/api/roadmaps/{id}/nodes
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/roadmaps/{id}/nodes")
  class AddNodeTests {

    private static final String NODES_URL = URL + "/7/nodes";

    @Test
    @DisplayName("shouldReturn200AndTheNode_whenCalledByAdmin_happyPath")
    void shouldAddForAdmin() throws Exception {
      RoadmapNodeDto created = nodeRequest();
      created.setId(11);
      created.setRoadmapId(7);
      when(roadmapService.addNodeToRoadmap(eq(7), any())).thenReturn(created);

      mockMvc
          .perform(
              asAdmin(post(NODES_URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json(nodeRequest())))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(11))
          .andExpect(jsonPath("$.roadmapId").value(7));
    }

    @Test
    @DisplayName("shouldReturn403AndNotAddTheNode_whenCallerIsAPlainUser")
    void shouldRejectRegularUser() throws Exception {
      mockMvc
          .perform(
              asRegularUser(post(NODES_URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json(nodeRequest())))
          .andExpect(status().isForbidden());

      verifyNoInteractions(roadmapService);
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldRejectGuest() throws Exception {
      mockMvc
          .perform(
              post(NODES_URL).contentType(MediaType.APPLICATION_JSON).content(json(nodeRequest())))
          .andExpect(status().isUnauthorized());

      verifyNoInteractions(roadmapService);
    }
  }

  // =====================================================================
  // GET /v1/api/roadmaps/{id}/nodes
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/roadmaps/{id}/nodes")
  class GetNodesTests {

    @Test
    @DisplayName("shouldReturn200AndTheNodes_whenCalledByAPlainUser_happyPath")
    void shouldReadAsRegularUser() throws Exception {
      RoadmapNodeDto dto = nodeRequest();
      dto.setId(11);
      dto.setRoadmapId(7);
      when(roadmapService.getRoadmapNodes(7)).thenReturn(List.of(dto));

      mockMvc
          .perform(asRegularUser(get(URL + "/7/nodes")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].name").value("Spring Boot"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledByAGuest")
    void shouldRejectGuest() throws Exception {
      mockMvc.perform(get(URL + "/7/nodes")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("shouldReturn400_whenRoadmapIdIsNotANumber")
    void shouldReturn400ForNonNumericId() throws Exception {
      mockMvc
          .perform(asRegularUser(get(URL + "/not-a-number/nodes")))
          .andExpect(status().isBadRequest());
    }
  }
}
