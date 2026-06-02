package com.socialapp.knowledge.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.knowledge.dto.CreateTokenRequestDto;
import com.socialapp.knowledge.dto.CreateTokenResponseDto;
import com.socialapp.knowledge.entity.PersonalAccessTokenEntity;
import com.socialapp.knowledge.service.PersonalAccessTokenService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/tokens")
@RequiredArgsConstructor
public class PersonalAccessTokenController {
  private final PersonalAccessTokenService tokenService;

  @PostMapping
  public CreateTokenResponseDto createToken(
      @RequestHeader("X-User-Id") Integer userId,
      @RequestBody @Valid CreateTokenRequestDto request) {
    return tokenService.createToken(userId, request);
  }

  @GetMapping
  public List<PersonalAccessTokenEntity> listTokens(@RequestHeader("X-User-Id") Integer userId) {
    return tokenService.listTokens(userId);
  }

  @DeleteMapping("/{tokenId}")
  public void revokeToken(
      @RequestHeader("X-User-Id") Integer userId, @PathVariable Integer tokenId) {
    tokenService.revokeToken(userId, tokenId);
  }
}
