package com.socialapp.newsfeed.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.socialapp.posts.dto.PublicQuizDetailsDto;
import com.socialapp.posts.entity.ArticleDetails;
import com.socialapp.posts.entity.CodeSnippetDetails;
import com.socialapp.posts.entity.EventDetails;
import com.socialapp.posts.entity.LinkDetails;
import com.socialapp.posts.entity.LocationDetails;
import com.socialapp.posts.entity.PollDetails;
import com.socialapp.posts.entity.QnaDetails;
import com.socialapp.posts.entity.enums.LocationType;
import com.socialapp.posts.entity.enums.PostType;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.posts.entity.enums.ReactionType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedPostDataDto {
  private Integer postId;
  private Integer authorId;

  /**
   * The author's handle, and the only key the public profile page has: {@code GET
   * /v1/api/users/{username}/profile} is looked up by username, never by id, and no endpoint maps
   * one to the other. Without this the author's name on a feed card rendered but led nowhere.
   *
   * <p>Costs no extra query — {@code FeedPostDataMapper} already holds the author's {@code
   * UserEntity} to read the four fields below it.
   */
  private String authorUsername;

  private String authorFullName;
  private String authorProfilePictureUrl;
  private Integer authorEliteScore;

  /**
   * The label that goes with {@link #authorEliteScore} ("Contributor", "Expert", …). Derived from
   * the score by {@link com.socialapp.reputation.RepLevel}, and sent rather than left to the
   * client so the threshold table stays in one place — a client computing it from the raw score
   * would be a second copy that drifts the moment a threshold moves.
   */
  private String authorLevelName;

  private String content;
  private PostVisibility visibility;
  private String googlePlaceId;
  private LocationType locationType;
  private LocationDetails locationDetails;
  private String googleMapsUrl;
  private PostType postType;
  private EventDetails eventDetails;
  private FeedBookSummaryDto book;

  /**
   * The quiz without its answers — see {@link PublicQuizDetailsDto}. This is deliberately not
   * the {@code QuizDetails} entity: that type carries {@code correctOptionIndex}, and this
   * payload is handed to readers who have not answered yet.
   */
  private PublicQuizDetailsDto quizDetails;

  private CodeSnippetDetails codeSnippetDetails;
  private ArticleDetails articleDetails;
  private QnaDetails qnaDetails;
  private PollDetails pollDetails;
  private LinkDetails linkDetails;
  private OffsetDateTime createdAt;

  /**
   * When the author last edited this post's content, or {@code null} for a post nobody has
   * edited.
   *
   * <p>Not cosmetic. Three things in this system point at the body of a post and are only true of
   * the body they were computed from: a skill verification whose proof is that post, a stored
   * Gemini explanation of it, and the reputation its reactions awarded its author. An edit that
   * arrives unannounced invalidates all three silently, and a reader has no way to tell that the
   * text in front of them is not the text that was verified.
   *
   * <p>Sourced from {@code PostEntity#editedAt}, not {@code PostEntity#updatedAt}. The latter is
   * Hibernate's {@code @UpdateTimestamp} and bumps on any write to the row — including
   * {@code ModerationEventListener}'s async moderation-status update, 1-2 seconds after every
   * post is created — which is not an edit. See B28 in {@code docs/backend-plan.md} for the
   * incident this replaced a timing heuristic with.
   */
  private OffsetDateTime updatedAt;

  private List<String> hashtags;

  /**
   * Echoed back so an edit does not destroy them.
   *
   * <p>{@code images} and {@code taggedUserIds} are both accepted by {@code UpdatePostRequest},
   * and {@code PostService.updatePost} applies that request with {@code
   * BeanUtils.copyProperties}, which copies nulls. A client can only send back what the feed gave
   * it, so while these two were missing here every edit silently cleared the post's images and
   * un-tagged everyone. Any field {@code UpdatePostRequest} accepts has to be readable from the
   * feed, or editing becomes a data-loss operation.
   */
  private List<String> images;

  private List<Integer> taggedUserIds;

  /**
   * Both are written at fan-out and kept current by {@code updateCachedLikeCount} / {@code
   * updateCachedCommentCount}.
   *
   * <p>{@code shareCount} used to sit beside them and was never written by anything — no share or
   * repost endpoint exists — so every post reported zero shares as if that were a measurement. It
   * was removed rather than left at zero: a field the client cannot distinguish from a real count
   * is worse than an absent one, and the feed card was already refusing to render it. Adding the
   * share feature means bringing it back here, writing it at fan-out the way these two are, and
   * restoring {@code NotificationType.POST_SHARED}.
   *
   * <p>Removing it from a cached DTO is only safe because {@code RedisConfig#cacheObjectMapper}
   * disables {@code FAIL_ON_UNKNOWN_PROPERTIES}: entries written before this change still carry
   * {@code "shareCount"} and would otherwise fail to deserialise for the whole 7-day cache life,
   * dropping those posts out of the feed with nothing but a WARN to show for it.
   */
  private int likeCount;

  private int commentCount;

  /**
   * How many reactions of each type this post has, e.g. {@code {"LIKE": 4, "INSIGHT": 2}} — the
   * breakdown behind {@link #likeCount}.
   *
   * <p>Exists because the reaction row dropped its text labels. While each chip read "Hữu ích 5"
   * it explained itself; with only a glyph and a number left, the one question a reader still has
   * is which reactions make up the five, and {@code likeCount} alone cannot answer it. {@code GET
   * /posts/{id}/reactions/summary} could, per post — ten cards, ten extra requests, over a
   * group-by this payload had already run and discarded the detail of.
   *
   * <p>Types nobody chose are absent rather than zero, matching {@code
   * PostReactionService#getReactionSummary}: the client renders one chip per entry and would have
   * to filter a zero out again.
   *
   * <p><b>Rewritten in the cache on every reaction</b> — see {@code
   * NewsfeedService.updateCachedReactionSummary}. The feed never falls back to Postgres, so a
   * breakdown written once at fan-out would disagree with the {@code likeCount} sitting beside it
   * the moment anybody reacted, which is worse than not sending one. Null on entries cached
   * before this field existed; a client has to treat that as "not known yet", not as "no
   * reactions" — {@code likeCount} remains the authoritative total.
   */
  private Map<ReactionType, Long> reactionSummary;
}
