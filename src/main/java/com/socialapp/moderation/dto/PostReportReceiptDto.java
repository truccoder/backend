package com.socialapp.moderation.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the reporter gets back.
 *
 * <p>Deliberately thin. It confirms the report was filed and nothing else — not how many other
 * people have reported the post, not whether the post has been pulled for review. Those numbers are
 * the escalation mechanism, and a client that can read them can probe for the threshold and
 * coordinate around it.
 *
 * @param reported always true when the call succeeded — filing twice is idempotent, so a repeat
 *     report is a success and not a 409. The first one is already on file and is what counts.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostReportReceiptDto {
  private boolean reported;
}
