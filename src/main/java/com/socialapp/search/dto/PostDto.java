package com.socialapp.search.dto;

import java.time.LocalDateTime;

import com.socialapp.posts.entity.ArticleDetails;
import com.socialapp.posts.entity.CodeSnippetDetails;
import com.socialapp.posts.entity.LinkDetails;
import com.socialapp.posts.entity.PollDetails;
import com.socialapp.posts.entity.QnaDetails;
import com.socialapp.posts.entity.QuizDetails;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostDto {
  private Integer id;
  private String content;
  private String eventName;
  private Integer authorId;
  private String authorFullName;
  private String authorProfilePictureUrl;
  private String visibility;
  private LocalDateTime createdAt;

  /**
   * Populated when this post is a book post — either it matched directly, or its book's
   * title/description matched the search query.
   */
  private BookDto book;

  private QuizDetails quizDetails;
  private CodeSnippetDetails codeSnippetDetails;
  private ArticleDetails articleDetails;
  private QnaDetails qnaDetails;
  private PollDetails pollDetails;
  private LinkDetails linkDetails;
}
