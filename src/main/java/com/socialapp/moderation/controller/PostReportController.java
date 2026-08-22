package com.socialapp.moderation.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.moderation.dto.CreatePostReportRequestDto;
import com.socialapp.moderation.dto.PostReportReceiptDto;
import com.socialapp.moderation.service.PostReportService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Reporting a post — the user-facing entrance to the moderation queue.
 *
 * <p>Sibling of {@link AppealController}, and the two are the two directions of the same system:
 * that one disputes a decision the moderators made, this one asks them to make one. Only the appeal
 * side existed, which meant a user could argue with moderation but never invoke it.
 *
 * <p>Signed-in only, deliberately, even though the posts a guest can read are exactly the ones most
 * likely to need reporting. The escalation threshold counts <i>people</i>, and an anonymous
 * reporter is not a person the system can count — it is an IP address, and three of those cost
 * nothing to obtain. Under {@code /v1/api/moderation} rather than nested under the post so that the
 * whole moderation surface a normal user touches sits in one place.
 */
@RestController
@RequestMapping("/v1/api/moderation/reports")
@RequiredArgsConstructor
public class PostReportController {

  private final PostReportService postReportService;

  @PostMapping
  public PostReportReceiptDto report(@Valid @RequestBody CreatePostReportRequestDto request) {
    postReportService.report(SecurityUtils.getCurrentUserId(), request);
    return new PostReportReceiptDto(true);
  }
}
