package com.socialapp.chat.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.chat.dto.ChatTokenResponse;
import com.socialapp.chat.service.StreamChatService;
import com.socialapp.security.util.SecurityUtils;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/chat")
@RequiredArgsConstructor
public class ChatController {

  private final StreamChatService streamChatService;

  @GetMapping("/token")
  public ChatTokenResponse getToken() {
    Integer userId = SecurityUtils.getCurrentUserId();
    String token = streamChatService.generateUserToken(userId);
    return ChatTokenResponse.builder().userId(String.valueOf(userId)).streamToken(token).build();
  }
}
