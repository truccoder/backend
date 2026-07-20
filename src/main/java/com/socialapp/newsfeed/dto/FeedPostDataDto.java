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
  private int likeCount;
  private int commentCount;
  private int shareCount;
}
