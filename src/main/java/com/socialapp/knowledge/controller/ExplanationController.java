package com.socialapp.knowledge.controller;

import org.springframework.web.bind.annotation.*;

import com.socialapp.knowledge.dto.ExplainRequestDto;
import com.socialapp.knowledge.dto.ExplanationResponseDto;
import com.socialapp.knowledge.dto.KnowledgeLibraryResponseDto;
import com.socialapp.knowledge.dto.SaveExplanationRequestDto;
import com.socialapp.knowledge.service.ExplanationService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/knowledge")
@RequiredArgsConstructor
public class ExplanationController {
  private final ExplanationService explanationService;

  /**
   * The body is optional — an explanation with no feedback and no stated language is the normal
   * first request — so both fields are read through a null check rather than assumed present.
   */
  @PostMapping("/posts/{postId}/explain")
  public ExplanationResponseDto explainPost(
      @PathVariable Integer postId,
      @Valid @RequestBody(required = false) ExplainRequestDto request) {
    String feedbackNote = request != null ? request.getFeedbackNote() : null;
    String language = request != null ? request.getLanguage() : null;
    // Stays a Boolean rather than collapsing to a primitive here: null ("caller said nothing")
    // and FALSE ("caller said no") mean different things to loadVaultContext, and an absent body
    // must keep meaning the former.
    Boolean useVaultContext = request != null ? request.getUseVaultContext() : null;
    return explanationService.explainPost(
        SecurityUtils.getCurrentUserId(), postId, feedbackNote, language, useVaultContext);
  }

  @PostMapping("/save")
  public ExplanationResponseDto saveExplanation(
      @RequestBody @Valid SaveExplanationRequestDto request) {
    return explanationService.saveExplanation(SecurityUtils.getCurrentUserId(), request);
  }

  @GetMapping("/my-library")
  public KnowledgeLibraryResponseDto getMyLibrary() {
    return explanationService.getLibrary(SecurityUtils.getCurrentUserId());
  }
}
