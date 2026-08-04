package com.socialapp.blocks.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.blocks.service.BlockService;
import com.socialapp.security.dto.PublicUserResponse;
import com.socialapp.security.util.SecurityUtils;

import lombok.RequiredArgsConstructor;

/**
 * Blocking. The list is always the caller's own — there is no path here for reading somebody
 * else's blocks, and there should not be: who has blocked whom is exactly the kind of fact that
 * would turn a safety tool into a way to find out you have been blocked.
 */
@RestController
@RequestMapping("/v1/api/blocks")
@RequiredArgsConstructor
public class BlockController {
  private final BlockService blockService;

  @GetMapping
  public List<PublicUserResponse> getBlockedUsers() {
    return blockService.getBlockedUsers(SecurityUtils.getCurrentUserId());
  }

  /** 204 on a repeat block: the caller asked for a state that is already true. */
  @PostMapping("/{userId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void block(@PathVariable Integer userId) {
    blockService.block(SecurityUtils.getCurrentUserId(), userId);
  }

  @DeleteMapping("/{userId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unblock(@PathVariable Integer userId) {
    blockService.unblock(SecurityUtils.getCurrentUserId(), userId);
  }
}
