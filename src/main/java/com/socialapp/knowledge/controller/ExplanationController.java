package com.socialapp.knowledge.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.knowledge.dto.ExplainRequestDto;
import com.socialapp.knowledge.dto.ExplanationResponseDto;
import com.socialapp.knowledge.dto.KnowledgeLibraryResponseDto;
import com.socialapp.knowledge.dto.SaveExplanationRequestDto;
import com.socialapp.knowledge.service.ExplanationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/knowledge")
@RequiredArgsConstructor
public class ExplanationController {
  private final ExplanationService explanationService;

  @PostMapping("/posts/{postId}/explain")
  public ExplanationResponseDto explainPost(
      @RequestHeader("X-User-Id") Integer userId,
      @PathVariable Integer postId,
      @RequestBody(required = false) ExplainRequestDto request) {
    String feedbackNote = request != null ? request.getFeedbackNote() : null;
    return explanationService.explainPost(userId, postId, feedbackNote);
  }

  @PostMapping("/save")
  public ExplanationResponseDto saveExplanation(
      @RequestHeader("X-User-Id") Integer userId,
      @RequestBody @Valid SaveExplanationRequestDto request) {
    return explanationService.saveExplanation(userId, request);
  }

  @GetMapping("/my-library")
  public KnowledgeLibraryResponseDto getMyLibrary(@RequestHeader("X-User-Id") Integer userId) {
    return explanationService.getLibrary(userId);
  }
}
