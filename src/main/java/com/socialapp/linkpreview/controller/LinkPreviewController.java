package com.socialapp.linkpreview.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.linkpreview.dto.LinkPreviewRequestDto;
import com.socialapp.linkpreview.dto.LinkPreviewResponseDto;
import com.socialapp.linkpreview.service.LinkPreviewService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Unfurling a pasted link.
 *
 * <p>Signed-in only, by the {@code .authenticated()} default in {@code SecurityConfig}, and that is
 * load-bearing rather than incidental. This endpoint makes the server fetch a URL of the caller's
 * choosing; the per-caller rate limit inside {@code LinkPreviewService} is keyed on a user id, and
 * an anonymous caller has none — the limit would be keyed on an IP address, which costs an attacker
 * nothing to change.
 */
@RestController
@RequestMapping("/v1/api/link-preview")
@RequiredArgsConstructor
public class LinkPreviewController {

  private final LinkPreviewService linkPreviewService;

  @PostMapping
  public LinkPreviewResponseDto preview(@Valid @RequestBody LinkPreviewRequestDto request) {
    return linkPreviewService.preview(SecurityUtils.getCurrentUserId(), request.url());
  }
}
