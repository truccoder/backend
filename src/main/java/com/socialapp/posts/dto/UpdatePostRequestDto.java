package com.socialapp.posts.dto;

import java.util.List;

import com.socialapp.posts.entity.ArticleDetails;
import com.socialapp.posts.entity.CodeSnippetDetails;
import com.socialapp.posts.entity.LinkDetails;
import com.socialapp.posts.entity.LocationDetails;
import com.socialapp.posts.entity.PollDetails;
import com.socialapp.posts.entity.QuizDetails;
import com.socialapp.posts.entity.enums.LocationType;
import com.socialapp.posts.entity.enums.PostVisibility;

import lombok.Data;

/**
 * The editable half of a post.
 *
 * <p><b>{@code qnaDetails} is deliberately absent, and must stay absent.</b> {@code
 * PostService#updatePost} copies every non-null property of this DTO straight onto the entity, so
 * a field here is a field the caller may overwrite with no further checks. All three of {@code
 * QnaDetails}' fields — {@code isResolved}, {@code acceptedAnswerId}, {@code bountyPoints} — are
 * <em>state produced by an action</em>, not content somebody composes, and each already has its
 * own endpoint that guards it: {@code POST /posts/{id}/accept-answer} refuses a comment from
 * another post and refuses a second accept, and {@code DELETE} of the same revokes the reputation
 * it granted.
 *
 * <p>While this field existed, an author could accept an answer (awarding the answerer), then send
 * {@code {"qnaDetails":{"acceptedAnswerId":null}}} here to clear it with no revoke, then accept a
 * different comment — minting {@code ACCEPTED_ANSWER} once per answer on the post. The reputation
 * ledger's unique key is {@code (userId, sourceType, sourceId)} and {@code sourceId} is the comment
 * id, so each loop looked like a new, legitimate signal.
 */
@Data
public class UpdatePostRequestDto {
  private String content;
  private String googlePlaceId;
  private LocationType locationType;
  private LocationDetails locationDetails;
  private PostVisibility visibility;
  private List<String> images;
  private List<Integer> taggedUserIds;
  private QuizDetails quizDetails;
  private CodeSnippetDetails codeSnippetDetails;
  private ArticleDetails articleDetails;
  private PollDetails pollDetails;
  private LinkDetails linkDetails;
}
