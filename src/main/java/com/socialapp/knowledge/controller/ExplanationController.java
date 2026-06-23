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

  @PostMapping("/posts/{postId}/explain")
  public ExplanationResponseDto explainPost(
      @PathVariable Integer postId, @RequestBody(required = false) ExplainRequestDto request) {
    String feedbackNote = request != null ? request.getFeedbackNote() : null;
    return explanationService.explainPost(SecurityUtils.getCurrentUserId(), postId, feedbackNote);
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
