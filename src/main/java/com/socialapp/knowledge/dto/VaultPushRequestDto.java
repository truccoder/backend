package com.socialapp.knowledge.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class VaultPushRequestDto {
  /**
   * The notes to sync, capped per request.
   *
   * <p>An Obsidian vault runs to thousands of notes and the plugin pushes the whole thing, so
   * without a ceiling the only limit was the servlet's max body size — an accidental one. The cap
   * matters twice over: {@code ExplanationService#loadVaultContext} concatenates every stored note
   * into the prompt of every {@code /explain} call, so vault size multiplies the token bill on an
   * endpoint that had no rate limit of its own, and a large enough vault simply overruns Gemini's
   * context window and breaks the feature for the users who invested most in it.
   *
   * <p>Clients with more than this sync in pages.
   */
  @NotEmpty
  @Size(max = 500, message = "At most 500 notes may be pushed in one request")
  private List<VaultNoteDto> notes;
}
