package com.socialapp.knowledge.controller;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import com.socialapp.knowledge.dto.CreateTokenRequestDto;
import com.socialapp.knowledge.dto.CreateTokenResponseDto;
import com.socialapp.knowledge.entity.PersonalAccessTokenEntity;
import com.socialapp.knowledge.service.PersonalAccessTokenService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/tokens")
@RequiredArgsConstructor
public class PersonalAccessTokenController {
  private final PersonalAccessTokenService tokenService;

  @PostMapping
  public CreateTokenResponseDto createToken(@RequestBody @Valid CreateTokenRequestDto request) {
    return tokenService.createToken(SecurityUtils.getCurrentUserId(), request);
  }

  @GetMapping
  public List<PersonalAccessTokenEntity> listTokens() {
    return tokenService.listTokens(SecurityUtils.getCurrentUserId());
  }

  @DeleteMapping("/{tokenId}")
  public void revokeToken(@PathVariable Integer tokenId) {
    tokenService.revokeToken(SecurityUtils.getCurrentUserId(), tokenId);
  }
}
