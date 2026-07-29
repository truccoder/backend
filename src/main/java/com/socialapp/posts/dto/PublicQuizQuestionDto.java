package com.socialapp.posts.dto;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.socialapp.posts.entity.QuizQuestion;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One quiz question with the answer removed — see {@link PublicQuizDetailsDto} for why.
 *
 * <p>{@code correctOptionIndex} is the obvious omission. {@code explanation} is the less obvious
 * one: an explanation almost always names the right answer, so shipping it before submission
 * would leak exactly what dropping {@code correctOptionIndex} was meant to protect. It is
 * returned with the result instead.
 */
/**
 * ignoreUnknown is for the feed cache, not cosmetic. Feed entries written before this type existed
 * still hold {@code correctOptionIndex} and {@code explanation} inside the cached JSON, and
 * without this every pre-existing quiz post would fail to deserialize on read and silently vanish
 * from the feed until its cache entry expired. With it, the stale fields are dropped on the way
 * out — so the old entries stop leaking the moment this ships, with no cache flush needed.
 *
 * <p>{@code RedisConfig#cacheObjectMapper} now disables FAIL_ON_UNKNOWN_PROPERTIES globally, which
 * covers this case too; kept here so the type stays safe under any mapper it is handed to.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicQuizQuestionDto {
  private String question;
  private List<String> options;

  public static PublicQuizQuestionDto from(QuizQuestion question) {
    if (Objects.isNull(question)) {
      return null;
    }

    return PublicQuizQuestionDto.builder()
        .question(question.getQuestion())
        .options(question.getOptions())
        .build();
  }
}
