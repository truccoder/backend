package com.socialapp.posts.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.posts.entity.QuizDetails;
import com.socialapp.posts.entity.QuizQuestion;

/**
 * Component (unit) tests for {@link PublicQuizDetailsDto}, per ISTQB CTFL v4.0.1 Section 2.2.1.
 *
 * <p>The last test is the one that matters: it asserts on the DTO's declared fields rather than
 * on a mapping, so adding {@code correctOptionIndex} back to the read type fails the build even
 * if every mapping call site still looks correct.
 */
class PublicQuizDetailsDtoTest {

  private static QuizDetails quizWithAnswers() {
    QuizQuestion question = new QuizQuestion();
    question.setQuestion("2 + 2?");
    question.setOptions(List.of("3", "4"));
    question.setCorrectOptionIndex(1);
    question.setExplanation("Two plus two is four");

    QuizDetails quiz = new QuizDetails();
    quiz.setTitle("Maths");
    quiz.setQuestions(List.of(question));
    return quiz;
  }

  @Test
  @DisplayName("should carry the title, question text and options across")
  void shouldCarryVisibleFields() {
    // When
    PublicQuizDetailsDto result = PublicQuizDetailsDto.from(quizWithAnswers());

    // Then
    assertThat(result.getTitle()).isEqualTo("Maths");
    assertThat(result.getQuestions()).hasSize(1);
    assertThat(result.getQuestions().get(0).getQuestion()).isEqualTo("2 + 2?");
    assertThat(result.getQuestions().get(0).getOptions()).containsExactly("3", "4");
  }

  @Test
  @DisplayName("should map a null quiz to null rather than an empty shell")
  void shouldMapNullToNull() {
    // When / Then — most posts carry no quiz, and an empty object would read as "quiz with no
    // questions" to the client
    assertThat(PublicQuizDetailsDto.from(null)).isNull();
  }

  @Test
  @DisplayName("should tolerate a quiz with no questions")
  void shouldTolerateNullQuestions() {
    // Given
    QuizDetails quiz = new QuizDetails();
    quiz.setTitle("Empty");

    // When / Then
    assertThat(PublicQuizDetailsDto.from(quiz).getQuestions()).isNull();
  }

  @Test
  @DisplayName("should not declare any field that would reveal the answer")
  void shouldNotDeclareAnswerFields() {
    // When
    List<String> readFields =
        Stream.of(PublicQuizQuestionDto.class.getDeclaredFields())
            .filter(field -> !field.isSynthetic())
            .map(java.lang.reflect.Field::getName)
            .toList();

    // Then — B5: correctOptionIndex in the feed payload meant devtools scored full marks, and
    // an explanation names the answer just as reliably. Both belong to QuizResultResponseDto.
    assertThat(readFields)
        .containsExactlyInAnyOrderElementsOf(Arrays.asList("question", "options"));
  }

  @Test
  @DisplayName("should drop answer fields left in cache entries written before this type existed")
  void shouldDropLegacyAnswerFields() throws Exception {
    // Given — exactly the shape sitting in Redis from before the fix. The app's ObjectMapper is
    // a plain new ObjectMapper(), which fails on unknown properties, so without ignoreUnknown
    // these entries would throw on read and the post would silently drop out of the feed.
    String legacyJson =
        "{\"title\":\"Legacy\",\"questions\":[{\"question\":\"2+2?\",\"options\":[\"3\",\"4\"],"
            + "\"correctOptionIndex\":1,\"explanation\":\"LEAKED\"}]}";

    // When
    PublicQuizDetailsDto result =
        new ObjectMapper().readValue(legacyJson, PublicQuizDetailsDto.class);

    // Then — readable again, and the answer is gone rather than passed through
    assertThat(result.getQuestions().get(0).getQuestion()).isEqualTo("2+2?");
    assertThat(new ObjectMapper().writeValueAsString(result))
        .doesNotContain("correctOptionIndex")
        .doesNotContain("LEAKED");
  }
}
