package com.socialapp.reputation.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.reputation.dto.ReputationResponseDto;
import com.socialapp.reputation.service.ReputationService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/users/{userId}/reputation")
@RequiredArgsConstructor
public class ReputationController {
  private final ReputationService reputationService;

  @GetMapping
  public ReputationResponseDto getReputation(@PathVariable Integer userId) {
    return reputationService.getReputation(userId);
  }
}
