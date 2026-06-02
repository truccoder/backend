package com.socialapp.knowledge.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.knowledge.dto.SyncResponseDto;
import com.socialapp.knowledge.dto.VaultPushRequestDto;
import com.socialapp.knowledge.service.KnowledgeSyncService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/knowledge/sync")
@RequiredArgsConstructor
public class KnowledgeSyncController {
  private final KnowledgeSyncService syncService;

  @GetMapping("/pull")
  public SyncResponseDto pull(
      @RequestHeader("Authorization") String authorization,
      @RequestParam(required = false) String since) {
    String token = authorization.replace("Bearer ", "");
    return syncService.pull(token, since);
  }

  @PostMapping("/push")
  public void push(
      @RequestHeader("Authorization") String authorization,
      @RequestBody @Valid VaultPushRequestDto request) {
    String token = authorization.replace("Bearer ", "");
    syncService.push(token, request);
  }
}
