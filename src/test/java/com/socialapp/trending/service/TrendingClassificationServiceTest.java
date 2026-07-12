package com.socialapp.trending.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.knowledge.client.GeminiClient;
import com.socialapp.trending.crawler.CrawledItem;
import com.socialapp.trending.entity.enums.TrendingCategory;
import com.socialapp.trending.entity.enums.TrendingSource;

/**
 * Component (unit) tests for {@link TrendingClassificationService}, per ISTQB CTFL v4.0.1
 * (Section 2.2.1 component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing,
 * Section 2.1.3 BDD Given/When/Then) — see {@code PostServiceTest} for the full rationale. A real
 * {@link ObjectMapper} is used (rather than mocked) since its only role here is parsing literal
 * JSON strings the tests construct — mocking it would just re-implement a JSON parser by hand.
 */
@ExtendWith(MockitoExtension.class)
class TrendingClassificationServiceTest {

  @Mock private GeminiClient geminiClient;

  private TrendingClassificationService classificationService;

  // A real ObjectMapper is used (see class Javadoc), so the service is constructed by hand rather
  // than via @InjectMocks — Mockito's injector only wires @Mock/@Spy fields into the constructor
  // and would otherwise pass null for this one. Built in @BeforeEach (not a field initializer)
  // since @Mock fields aren't injected yet when field initializers run.
  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    classificationService = new TrendingClassificationService(geminiClient, new ObjectMapper());
  }

  private static CrawledItem item(String title) {
    return CrawledItem.builder()
        .title(title)
        .summary("summary")
        .source(TrendingSource.HACKER_NEWS)
        .build();
  }

  private static List<CrawledItem> items(int count) {
    return IntStream.range(0, count).mapToObj(i -> item("Item " + i)).toList();
  }

  // =====================================================================
  // classify
  // =====================================================================

  @Nested
  @DisplayName("classify")
  class ClassifyTests {

    @Test
    @DisplayName("should return the parsed category when Gemini responds successfully")
    void shouldReturnParsedCategory_whenGeminiRespondsSuccessfully() {
      // Given
      when(geminiClient.generateContent(any()))
          .thenReturn("{\"category\": \"OPENSOURCE\", \"tags\": [\"java\"]}");

      // When / Then
      assertThat(classificationService.classify(item("Repo")))
          .isEqualTo(TrendingCategory.OPENSOURCE);
    }

    @Test
    @DisplayName("should default to OTHER when Gemini throws")
    void shouldReturnOther_whenGeminiThrows() {
      // Given
      when(geminiClient.generateContent(any())).thenThrow(new RuntimeException("timeout"));

      // When / Then
      assertThat(classificationService.classify(item("Repo"))).isEqualTo(TrendingCategory.OTHER);
    }

    @Test
    @DisplayName("should default to OTHER when the category field is missing")
    void shouldDefaultToOther_whenCategoryFieldMissing() {
      // Given
      when(geminiClient.generateContent(any())).thenReturn("{\"tags\": [\"java\"]}");

      // When / Then
      assertThat(classificationService.classify(item("Repo"))).isEqualTo(TrendingCategory.OTHER);
    }

    @Test
    @DisplayName("should default to OTHER when the category value is not a known enum constant")
    void shouldDefaultToOther_whenCategoryValueInvalid() {
      // Given
      when(geminiClient.generateContent(any())).thenReturn("{\"category\": \"NOT_A_CATEGORY\"}");

      // When / Then
      assertThat(classificationService.classify(item("Repo"))).isEqualTo(TrendingCategory.OTHER);
    }

    @Test
    @DisplayName("should default to OTHER when the response is not valid JSON")
    void shouldDefaultToOther_whenResponseIsNotValidJson() {
      // Given
      when(geminiClient.generateContent(any())).thenReturn("not json at all");

      // When / Then
      assertThat(classificationService.classify(item("Repo"))).isEqualTo(TrendingCategory.OTHER);
    }
  }

  // =====================================================================
  // classifyBatch
  // =====================================================================

  @Nested
  @DisplayName("classifyBatch")
  class ClassifyBatchTests {

    @Test
    @DisplayName("should classify in a single call when item count is at the batch size")
    void shouldClassifyInOneBatch_whenItemCountAtBatchSize() {
      // Given
      String response =
          "{\"classifications\": [{\"index\": 1, \"category\": \"TOOL\"}, {\"index\": 2, \"category\": \"CAREER\"}]}";
      when(geminiClient.generateContent(any())).thenReturn(response);

      // When
      List<TrendingCategory> result = classificationService.classifyBatch(items(2));

      // Then
      assertThat(result).containsExactly(TrendingCategory.TOOL, TrendingCategory.CAREER);
      org.mockito.Mockito.verify(geminiClient, org.mockito.Mockito.times(1)).generateContent(any());
    }

    @Test
    @DisplayName("should split into multiple calls when item count exceeds the batch size")
    void shouldSplitIntoMultipleBatches_whenItemCountExceedsBatchSize() {
      // Given
      when(geminiClient.generateContent(any())).thenReturn("{\"classifications\": []}");

      // When
      List<TrendingCategory> result = classificationService.classifyBatch(items(15));

      // Then
      assertThat(result).hasSize(15);
      org.mockito.Mockito.verify(geminiClient, org.mockito.Mockito.times(2)).generateContent(any());
    }

    @Test
    @DisplayName("should default the whole batch to OTHER when Gemini throws")
    void shouldDefaultBatchToOther_whenGeminiThrows() {
      // Given
      when(geminiClient.generateContent(any())).thenThrow(new RuntimeException("timeout"));

      // When
      List<TrendingCategory> result = classificationService.classifyBatch(items(3));

      // Then
      assertThat(result).containsOnly(TrendingCategory.OTHER);
    }

    @Test
    @DisplayName("should default to OTHER for every item when classifications is not an array")
    void shouldDefaultToOther_whenClassificationsIsNotArray() {
      // Given
      when(geminiClient.generateContent(any())).thenReturn("{\"classifications\": \"oops\"}");

      // When
      List<TrendingCategory> result = classificationService.classifyBatch(items(2));

      // Then
      assertThat(result).containsExactly(TrendingCategory.OTHER, TrendingCategory.OTHER);
    }

    @Test
    @DisplayName("should ignore an out-of-range index and keep that slot as OTHER")
    void shouldIgnoreOutOfRangeIndex() {
      // Given — index 0 (-> 0-based -1, invalid) and index 99 (-> 98, out of range) are both
      // ignored; only index 1 (0-based 0) lands in range.
      String response =
          "{\"classifications\": ["
              + "{\"index\": 0, \"category\": \"TOOL\"},"
              + "{\"index\": 99, \"category\": \"TOOL\"},"
              + "{\"index\": 1, \"category\": \"CAREER\"}"
              + "]}";
      when(geminiClient.generateContent(any())).thenReturn(response);

      // When
      List<TrendingCategory> result = classificationService.classifyBatch(items(2));

      // Then
      assertThat(result).containsExactly(TrendingCategory.CAREER, TrendingCategory.OTHER);
    }

    @Test
    @DisplayName("should ignore an invalid category value at a valid index, keeping it OTHER")
    void shouldIgnoreInvalidCategoryValue_atValidIndex() {
      // Given
      String response = "{\"classifications\": [{\"index\": 1, \"category\": \"NOT_REAL\"}]}";
      when(geminiClient.generateContent(any())).thenReturn(response);

      // When
      List<TrendingCategory> result = classificationService.classifyBatch(items(1));

      // Then
      assertThat(result).containsExactly(TrendingCategory.OTHER);
    }

    @Test
    @DisplayName("should default every item to OTHER when the response JSON is invalid")
    void shouldDefaultAllToOther_whenResponseJsonInvalid() {
      // Given
      when(geminiClient.generateContent(any())).thenReturn("not json");

      // When
      List<TrendingCategory> result = classificationService.classifyBatch(items(3));

      // Then
      assertThat(result).containsOnly(TrendingCategory.OTHER);
    }
  }
}
