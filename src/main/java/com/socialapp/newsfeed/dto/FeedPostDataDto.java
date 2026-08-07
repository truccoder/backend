package com.socialapp.newsfeed.dto;

import java.time.OffsetDateTime;
import java.util.List;

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
}
