package com.socialapp.moderation.dto;

import com.socialapp.moderation.enums.ReportReason;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePostReportRequestDto {

  @NotNull(message = "postId is required")
  private Integer postId;

  @NotNull(message = "reason is required")
  private ReportReason reason;

  /**
   * What the reporter wanted to add in their own words. Optional for every reason including {@code
   * OTHER} — requiring prose there would turn "this is wrong and I do not know the word for it"
   * into a form the reporter abandons, and an empty {@code OTHER} report still carries the one
   * signal that matters: a person objected.
   */
  @Size(max = 1000, message = "details must be at most 1000 characters")
  private String details;
}
