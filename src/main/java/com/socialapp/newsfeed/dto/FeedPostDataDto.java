package com.socialapp.newsfeed.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.socialapp.posts.entity.ArticleDetails;
import com.socialapp.posts.entity.CodeSnippetDetails;
import com.socialapp.posts.entity.EventDetails;
import com.socialapp.posts.entity.LinkDetails;
import com.socialapp.posts.entity.LocationDetails;
import com.socialapp.posts.entity.PollDetails;
import com.socialapp.posts.entity.QnaDetails;
import com.socialapp.posts.entity.QuizDetails;
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
  private String content;
  private PostVisibility visibility;
  private String googlePlaceId;
  private LocationType locationType;
  private LocationDetails locationDetails;
  private String googleMapsUrl;
  private PostType postType;
  private EventDetails eventDetails;
  private FeedBookSummaryDto book;
  private QuizDetails quizDetails;
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

  private int likeCount;
  private int commentCount;
  private int shareCount;
}
