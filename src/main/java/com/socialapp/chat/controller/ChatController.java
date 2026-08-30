package com.socialapp.chat.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.chat.dto.ChatTokenResponse;
import com.socialapp.chat.dto.CreateGroupChatRequest;
import com.socialapp.chat.dto.GroupChatResponse;
import com.socialapp.chat.service.StreamChatService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
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

  /**
   * Creates a named group conversation with the caller as its owner.
   *
   * <p>The only chat channel this backend creates itself. A direct message does not need it — the
   * frontend opens that against Stream once {@link #ensureParticipants} has introduced the pair —
   * but a group does, for two reasons the browser cannot cover: the channel's owner is whatever
   * {@code created_by_id} the client sends, and checking blocks a pair at a time misses the ones
   * between two invited members, which is exactly the case that would put two people who blocked
   * each other in the same room.
   *
   * <p>Returns <b>201</b> with the channel handle; the frontend then calls {@code channel.watch()}
   * with its own Stream token as it would for any other channel. Note the two ways a bad member
   * list is rejected: a list that breaks {@code @Valid} (empty, over 99, a negative id) is
   * <b>422</b>, while a list that only turns out to be too short after duplicates and the caller
   * are removed is <b>400</b> — bean validation cannot see that {@code [7, 7]} is one person.
   */
  @PostMapping("/groups")
  @ResponseStatus(HttpStatus.CREATED)
  public GroupChatResponse createGroup(@Valid @RequestBody CreateGroupChatRequest request) {
    return streamChatService.createGroupChat(
        SecurityUtils.getCurrentUserId(),
        request.getName(),
        request.getImageUrl(),
        request.getMemberIds());
  }
}
