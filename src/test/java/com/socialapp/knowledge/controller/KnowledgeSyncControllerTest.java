package com.socialapp.knowledge.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialapp.knowledge.dto.ExplanationResponseDto;
import com.socialapp.knowledge.dto.SyncResponseDto;
import com.socialapp.knowledge.service.KnowledgeSyncService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link KnowledgeSyncController}, per ISTQB CTFL v4.0.1
 * Section 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link KnowledgeSyncService} is
 * mocked.
 *
 * <p>Unlike every other controller in this app, this one is <b>not</b> authenticated via the
 * standard JWT/{@code SecurityUtils.getCurrentUserId()} path — it accepts a Personal Access
 * Token in the {@code Authorization} header, extracted manually and validated inside {@link
 * KnowledgeSyncService} (mocked here, so its internal token validation never actually runs).
 * These tests therefore never stub {@link JwtProvider}, matching the real Obsidian-plugin
 * caller, which has a PAT, not a JWT.
 */
@WebMvcTest(KnowledgeSyncController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class KnowledgeSyncControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private KnowledgeSyncService syncService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private UserRepository userRepository;

  private static final String SYNC_URL = "/v1/api/knowledge/sync";
  private static final String PAT_HEADER = "Bearer sk_somepersonalaccesstoken";

  // =====================================================================
  // GET /v1/api/knowledge/sync/pull
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/knowledge/sync/pull")
  class PullTests {

    @Test
    @DisplayName("shouldReturn200_whenSinceIsNotProvided_happyPath")
    void shouldReturn200_whenSinceIsNotProvided_happyPath() throws Exception {
      // Given
      when(syncService.pull(eq("sk_somepersonalaccesstoken"), isNull()))
          .thenReturn(
              SyncResponseDto.builder()
                  .explanations(List.of())
                  .syncedAt(OffsetDateTime.parse("2026-07-12T00:00:00Z"))
                  .build());

      // When / Then
      mockMvc
          .perform(get(SYNC_URL + "/pull").header("Authorization", PAT_HEADER))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.explanations").isEmpty());
    }

    @Test
    @DisplayName("shouldReturn200_whenSinceIsProvided_happyPath")
    void shouldReturn200_whenSinceIsProvided_happyPath() throws Exception {
      // Given
      when(syncService.pull(eq("sk_somepersonalaccesstoken"), eq("2026-07-01T00:00:00Z")))
          .thenReturn(
              SyncResponseDto.builder()
                  .explanations(
                      List.of(
                          ExplanationResponseDto.builder()
                              .id(1)
                              .postId(1)
                              .originalContent("Original content")
                              .explanationContent("Explained content")
                              .version(1)
                              .build()))
                  .syncedAt(OffsetDateTime.parse("2026-07-12T00:00:00Z"))
                  .build());

      // When / Then
      mockMvc
          .perform(
              get(SYNC_URL + "/pull")
                  .header("Authorization", PAT_HEADER)
                  .param("since", "2026-07-01T00:00:00Z"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.explanations[0].id").value(1));
    }

    @Test
    @DisplayName("shouldReturn400_whenAuthorizationHeaderIsMissing")
    void shouldReturn400_whenAuthorizationHeaderIsMissing() throws Exception {
      // When / Then
      mockMvc
          .perform(get(SYNC_URL + "/pull"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("Missing required header 'Authorization'"));
    }
  }

  // =====================================================================
  // POST /v1/api/knowledge/sync/push
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/knowledge/sync/push")
  class PushTests {

    @Test
    @DisplayName("shouldReturn200_whenPayloadIsValid_happyPath")
    void shouldReturn200_whenPayloadIsValid_happyPath() throws Exception {
      // Given
      String requestJson =
          """
          {
            "notes": [
              { "filename": "note1.md", "content": "Hello", "tags": ["tag1"], "links": [] }
            ]
          }
          """;

      // When / Then
      mockMvc
          .perform(
              post(SYNC_URL + "/push")
                  .header("Authorization", PAT_HEADER)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("shouldReturn422_whenNotesIsEmpty")
    void shouldReturn422_whenNotesIsEmpty() throws Exception {
      // Given
      String requestJson =
          """
          { "notes": [] }
          """;

      // When / Then
      mockMvc
          .perform(
              post(SYNC_URL + "/push")
                  .header("Authorization", PAT_HEADER)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn422_whenNotesIsMissing")
    void shouldReturn422_whenNotesIsMissing() throws Exception {
      // Given
      String requestJson = "{}";

      // When / Then
      mockMvc
          .perform(
              post(SYNC_URL + "/push")
                  .header("Authorization", PAT_HEADER)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn400_whenAuthorizationHeaderIsMissing")
    void shouldReturn400_whenAuthorizationHeaderIsMissing() throws Exception {
      // Given
      String requestJson =
          """
          {
            "notes": [
              { "filename": "note1.md", "content": "Hello", "tags": ["tag1"], "links": [] }
            ]
          }
          """;

      // When / Then
      mockMvc
          .perform(
              post(SYNC_URL + "/push").contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("Missing required header 'Authorization'"));
    }
  }
}
