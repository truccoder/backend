package com.socialapp.knowledge.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.knowledge.dto.VaultNoteDetailDto;
import com.socialapp.knowledge.dto.VaultNotePageResponseDto;
import com.socialapp.knowledge.dto.VaultNoteSummaryDto;
import com.socialapp.knowledge.service.VaultNoteService;
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
 * System/API integration tests for {@link VaultNoteController}, per ISTQB CTFL v4.0.1 Section
 * 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link VaultNoteService} is mocked.
 *
 * <p><b>{@link RequiresAuthTests} IS THE TEST THAT MATTERS MOST HERE, and it is testing {@code
 * SecurityConfig} more than this controller.</b> Its sibling {@code KnowledgeSyncController} sits
 * on {@code permitAll} because the Obsidian plugin authenticates with a personal access token
 * instead of a session. These endpoints read and delete the same rows and must be the opposite —
 * so the path deliberately lives at {@code /knowledge/vault/**}, outside any {@code sync/**}
 * matcher. A refactor that moved it under {@code sync} would open somebody's personal notes to
 * anonymous callers, and nothing else in the suite would notice.
 */
@WebMvcTest(VaultNoteController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class VaultNoteControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private VaultNoteService vaultNoteService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private BanDetailsService banDetailsService;
  @MockBean private UserRepository userRepository;

  private static final String VAULT_URL = "/v1/api/knowledge/vault";
  private static final String VALID_TOKEN = "a-valid-jwt-token";

  private UserEntity currentUser;

  @BeforeEach
  void setUpDefaultUser() {
    currentUser = new UserEntity();
    currentUser.setId(1);
    currentUser.setEmail("learner@example.com");
    currentUser.setUsername("learner");
    currentUser.setFullName("Learner One");
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

  private static VaultNoteSummaryDto summary(Integer id, String filename) {
    return new VaultNoteSummaryDto(
        id, filename, List.of("backend"), List.of(), OffsetDateTime.now(), OffsetDateTime.now());
  }

  @Nested
  @DisplayName("GET /v1/api/knowledge/vault/notes")
  class ListNotesTests {

    @Test
    @DisplayName("shouldReturnAPageOfNotes_happyPath")
    void shouldReturnPage() throws Exception {
      // Given
      when(vaultNoteService.listNotes(eq(currentUser.getId()), isNull(), eq(10)))
          .thenReturn(new VaultNotePageResponseDto(List.of(summary(7, "jpa-tuning.md")), 7, false));

      // When / Then
      mockMvc
          .perform(authed(get(VAULT_URL + "/notes")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items[0].filename").value("jpa-tuning.md"))
          .andExpect(jsonPath("$.nextCursor").value(7))
          .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    @DisplayName("shouldNeverSerialiseNoteBodiesInTheList_security")
    void shouldNotShipBodies() throws Exception {
      // Given — the summary record has no content component, so a body cannot leak into a listing
      // even if one were attached to the entity
      when(vaultNoteService.listNotes(eq(currentUser.getId()), isNull(), eq(10)))
          .thenReturn(new VaultNotePageResponseDto(List.of(summary(7, "a.md")), 7, false));

      // When / Then
      mockMvc
          .perform(authed(get(VAULT_URL + "/notes")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items[0].content").doesNotExist());
    }

    @Test
    @DisplayName("shouldPassTheCursorAndLimitThrough_happyPath")
    void shouldPassPagingThrough() throws Exception {
      // Given
      when(vaultNoteService.listNotes(eq(currentUser.getId()), eq(42), eq(25)))
          .thenReturn(new VaultNotePageResponseDto(List.of(), null, false));

      // When / Then
      mockMvc
          .perform(authed(get(VAULT_URL + "/notes").param("cursor", "42").param("limit", "25")))
          .andExpect(status().isOk());

      verify(vaultNoteService).listNotes(eq(currentUser.getId()), eq(42), eq(25));
    }

    @Test
    @DisplayName("shouldReject_whenLimitExceedsTheCap_boundary")
    void shouldRejectOversizedLimit() throws Exception {
      // Given — BVA on @Max(50): a vault is unbounded, so an uncapped page is a request the
      // caller gets to size

      // When / Then
      mockMvc
          .perform(authed(get(VAULT_URL + "/notes").param("limit", "51")))
          .andExpect(status().is4xxClientError());

      verify(vaultNoteService, never()).listNotes(eq(currentUser.getId()), isNull(), eq(51));
    }
  }

  @Nested
  @DisplayName("GET /v1/api/knowledge/vault/notes/{noteId}")
  class GetNoteTests {

    @Test
    @DisplayName("shouldReturnTheNoteWithItsBody_happyPath")
    void shouldReturnBody() throws Exception {
      // Given
      when(vaultNoteService.getNote(currentUser.getId(), 7))
          .thenReturn(
              new VaultNoteDetailDto(
                  7,
                  "jpa-tuning.md",
                  "# N+1",
                  List.of("backend"),
                  List.of(),
                  OffsetDateTime.now(),
                  OffsetDateTime.now()));

      // When / Then
      mockMvc
          .perform(authed(get(VAULT_URL + "/notes/7")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content").value("# N+1"));
    }

    @Test
    @DisplayName("shouldReturn404_whenTheNoteBelongsToAnotherUser_security")
    void shouldReturn404ForForeignNote() throws Exception {
      // Given — the service answers 404 rather than 403 on purpose: a 403 would confirm the id
      // exists, which is enough to map another person's vault by walking the sequence
      when(vaultNoteService.getNote(currentUser.getId(), 999))
          .thenThrow(new NotFoundException("Vault note not found"));

      // When / Then
      mockMvc.perform(authed(get(VAULT_URL + "/notes/999"))).andExpect(status().isNotFound());
    }
  }

  @Nested
  @DisplayName("DELETE /v1/api/knowledge/vault/notes")
  class DeleteTests {

    @Test
    @DisplayName("shouldDeleteOneNote_happyPath")
    void shouldDeleteOne() throws Exception {
      // When / Then
      mockMvc.perform(authed(delete(VAULT_URL + "/notes/7"))).andExpect(status().isOk());

      verify(vaultNoteService).deleteNote(currentUser.getId(), 7);
    }

    @Test
    @DisplayName("shouldReturnTheNumberRemoved_whenWipingTheVault_happyPath")
    void shouldReportWipeCount() throws Exception {
      // Given — the count is the only confirmation available for an action whose visible effect
      // is that a list becomes empty
      when(vaultNoteService.deleteAllNotes(currentUser.getId())).thenReturn(431);

      // When / Then
      mockMvc
          .perform(authed(delete(VAULT_URL + "/notes")))
          .andExpect(status().isOk())
          .andExpect(content().string("431"));
    }
  }

  @Nested
  @DisplayName("authentication")
  class RequiresAuthTests {

    @Test
    @DisplayName("shouldReturn401_whenListingWithNoSession_security")
    void listRequiresSession() throws Exception {
      // When / Then — unlike /knowledge/sync/**, this path is NOT on permitAll and must not become
      // so: it reads personal notes with the caller's session, not with a vault token
      mockMvc.perform(get(VAULT_URL + "/notes")).andExpect(status().isUnauthorized());

      verify(vaultNoteService, never()).listNotes(eq(currentUser.getId()), isNull(), eq(10));
    }

    @Test
    @DisplayName("shouldReturn401_whenDeletingWithNoSession_security")
    void deleteRequiresSession() throws Exception {
      // When / Then
      mockMvc.perform(delete(VAULT_URL + "/notes/7")).andExpect(status().isUnauthorized());

      verify(vaultNoteService, never()).deleteNote(currentUser.getId(), 7);
    }

    @Test
    @DisplayName("shouldReturn401_whenWipingWithNoSession_security")
    void wipeRequiresSession() throws Exception {
      // When / Then — the most destructive endpoint in the feature, checked explicitly
      mockMvc.perform(delete(VAULT_URL + "/notes")).andExpect(status().isUnauthorized());

      verify(vaultNoteService, never()).deleteAllNotes(currentUser.getId());
    }
  }
}
