package com.socialapp.posts.dto;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.socialapp.posts.entity.QuizDetails;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A quiz as it may be shown to somebody who has not answered it yet.
 *
 * <p>This type exists for one reason: {@link QuizDetails} holds the answers. Serialising the
 * entity straight into the feed and search payloads meant {@code correctOptionIndex} — and any
 * {@code explanation} that gives the answer away — arrived in the browser before the reader had
 * picked anything, so opening devtools was enough to score full marks. Hiding those fields in the
 * client would only have been a performance of security.
 *
 * <p>Everything the reader is not entitled to yet is therefore absent from the wire, not merely
 * unused: only the question text and the options survive. The answers and explanations come back
 * from {@code POST /v1/api/posts/{postId}/quiz/submit} once an attempt has been recorded, which
 * is the only point at which revealing them is harmless.
 *
 * <p>Never widen this DTO to "just add one more field" from {@link QuizDetails}. Each field here
 * is a field the server has decided a non-participant may see.
 */
// Same reason as PublicQuizQuestionDto: cached feed entries predate this type.
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicQuizDetailsDto {
  private String title;
  private List<PublicQuizQuestionDto> questions;

  public static PublicQuizDetailsDto from(QuizDetails quiz) {
    if (Objects.isNull(quiz)) {
      return null;
    }

    return PublicQuizDetailsDto.builder()
        .title(quiz.getTitle())
        .questions(
            Objects.isNull(quiz.getQuestions())
                ? null
                : quiz.getQuestions().stream().map(PublicQuizQuestionDto::from).toList())
        .build();
  }
}
