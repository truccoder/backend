package com.socialapp.chat.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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
    return streamChatService.issueToken(SecurityUtils.requireCurrentUser());
  }

  /**
   * Call this before opening a channel with someone who is not already a friend.
   *
   * <p>{@code GET /token} syncs the caller and their friends to Stream, which is why chat only ever
   * worked between friends: Stream refuses to create a channel containing a user it has not seen.
   * Now that the app is open, the counterpart can be anyone, and the only way to introduce an
   * arbitrary pair without upserting the whole user table per token request is to ask for the pair
   * by name.
   *
   * <p>Safe to call for a friend too — the upsert is idempotent — so the frontend does not have to
   * know whether the two are friends before opening a conversation.
   */
  @PostMapping("/participants/{userId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void ensureParticipants(@PathVariable Integer userId) {
    streamChatService.ensureChatParticipants(SecurityUtils.getCurrentUserId(), userId);
  }
}
