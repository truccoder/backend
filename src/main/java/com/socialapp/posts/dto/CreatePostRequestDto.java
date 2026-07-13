package com.socialapp.posts.dto;

import java.util.List;

import com.socialapp.bookstore.dto.CreateBookRequestDto;
import com.socialapp.posts.entity.EventDetails;
import com.socialapp.posts.entity.LocationDetails;
import com.socialapp.posts.entity.QuizDetails;
import com.socialapp.posts.entity.enums.LocationType;
import com.socialapp.posts.entity.enums.PostType;
import com.socialapp.posts.entity.enums.PostVisibility;

import lombok.Data;

@Data
public class CreatePostRequestDto {
  private String content;
  private String googlePlaceId;
  private LocationType locationType;
  private LocationDetails locationDetails;
  private PostVisibility visibility;
  private List<String> images;
  private List<Integer> taggedUserIds;
  private PostType postType;
  private EventDetails eventDetails;
  private CreateBookRequestDto bookDetails;
  private QuizDetails quizDetails;
}
