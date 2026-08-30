package com.socialapp.search.dto;

import java.time.OffsetDateTime;
import java.util.Map;

import com.socialapp.posts.dto.PublicQuizDetailsDto;
import com.socialapp.posts.entity.ArticleDetails;
import com.socialapp.posts.entity.CodeSnippetDetails;
import com.socialapp.posts.entity.LinkDetails;
import com.socialapp.posts.entity.PollDetails;
import com.socialapp.posts.entity.QnaDetails;
import com.socialapp.posts.entity.enums.ReactionType;

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

  /** Deep-link key for the author's profile — same reasoning as the feed payload's field. */
  private String authorUsername;

  private String authorFullName;
  private String authorProfilePictureUrl;
  private Integer authorEliteScore;

  /** Label for {@link #authorEliteScore} — same reasoning as the feed payload's field. */
  private String authorLevelName;

  private String visibility;

  /**
   * Offset-carrying on purpose. This was the only {@code createdAt} in the backend declared as
   * {@code LocalDateTime}: {@code created_at} is a {@code timestamptz}, so
   * {@code OffsetDateTime.toLocalDateTime()} dropped the zone and Jackson serialised a bare
   * {@code 2026-07-27T18:24:54}. Every client that parses that string reads it as local time, so a
   * fresh post showed up "7 hours ago" on a UTC+7 machine — off by exactly the reader's offset,
   * and invisible on a machine sitting at UTC. Keep the offset here so the wire format matches the
   * other 30+ DTOs.
   */
  private OffsetDateTime createdAt;

  /**
   * Populated when this post is a book post — either it matched directly, or its book's
   * title/description matched the search query.
   */
  private BookDto book;

  /** Answer-free quiz, same reasoning as the feed payload — see {@link PublicQuizDetailsDto}. */
  private PublicQuizDetailsDto quizDetails;

  private CodeSnippetDetails codeSnippetDetails;
  private ArticleDetails articleDetails;
  private QnaDetails qnaDetails;
  private PollDetails pollDetails;
  private LinkDetails linkDetails;

  /**
   * The per-type reaction breakdown, the same field {@code FeedPostDataDto} carries and for the
   * same reason — a search result renders the same card as a feed row, so a field present on one
   * and missing on the other is a card that loses its reaction chips when the reader arrives by
   * searching instead of scrolling.
   *
   * <p>Types nobody chose are absent rather than zero.
   */
  private Map<ReactionType, Long> reactionSummary;
}
