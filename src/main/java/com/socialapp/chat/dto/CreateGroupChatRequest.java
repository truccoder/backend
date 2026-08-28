package com.socialapp.chat.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** What the frontend sends to open a named group conversation. */
@Data
public class CreateGroupChatRequest {

  /**
   * The group's display name, shown in the channel list instead of the members' names.
   *
   * <p>Required, and that is the line between this endpoint and {@code POST /participants/{id}}: a
   * conversation without a name is a direct message, which the frontend already creates against
   * Stream itself once both people are synced.
   */
  @NotBlank
  @Size(max = 100, message = "A group name may be at most 100 characters")
  private String name;

  /**
   * Everyone in the group <em>except</em> the caller, who is added as owner automatically.
   *
   * <p>At least two, because caller + one other is a direct message wearing a name — routing it
   * through here would produce a second channel between two people who already have one, and the
   * unread badge would then be split across both. See {@code StreamChatService#MIN_OTHER_MEMBERS}.
   *
   * <p>The ceiling is Stream's, not ours: a {@code messaging} channel holds 100 members by default,
   * and the caller occupies one of those seats. Sending more gets the whole request rejected rather
   * than silently truncated — a group missing three people looks like a bug in the member picker
   * and nobody would think to count.
   *
   * <p>Duplicates and the caller's own id are tolerated and removed, so the frontend does not have
   * to de-duplicate a selection list that may legitimately contain the same person twice (picked
   * from search, then from the friend list).
   */
  @NotEmpty
  @Size(min = 2, max = 99, message = "A group needs between 2 and 99 members besides yourself")
  private List<@NotNull @Positive Integer> memberIds;

  /** Optional group avatar. Passed to Stream as the channel {@code image}. */
  @Size(max = 512, message = "The group image URL may be at most 512 characters")
  private String imageUrl;
}
