package com.socialapp.chat.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The handle the frontend needs to open the channel that was just created.
 *
 * <p>Deliberately not the channel's full state: the frontend holds a Stream user token and calls
 * {@code channel.watch()} itself, which returns the members, the read state and the message history
 * in the shape its SDK expects. Mirroring any of that here would be a second copy that goes stale
 * the moment somebody sends a message.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupChatResponse {

  /** Always {@code messaging}; returned so the frontend does not hard-code it. */
  private String channelType;

  private String channelId;

  /** {@code type:id} — the form Stream's SDK and its webhooks use to name a channel. */
  private String cid;

  private String name;

  /**
   * Everyone in the channel, caller included, as strings — Stream user ids are strings, and handing
   * back numbers here would mean the frontend converts them before every SDK call.
   */
  private List<String> memberIds;

  /** The caller. Stream makes the creator the channel owner. */
  private String createdBy;
}
