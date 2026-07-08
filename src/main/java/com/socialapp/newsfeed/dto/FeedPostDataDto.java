package com.socialapp.newsfeed.dto;

import java.time.OffsetDateTime;

import com.socialapp.posts.entity.EventDetails;
import com.socialapp.posts.entity.LocationDetails;
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
  private OffsetDateTime createdAt;
  private int likeCount;
  private int commentCount;
  private int shareCount;
}
