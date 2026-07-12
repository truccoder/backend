package com.socialapp.moderation.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.knowledge.client.GeminiClient;
import com.socialapp.knowledge.config.GeminiProperties;
import com.socialapp.moderation.dto.ModerationScores;

/**
 * Component (unit) tests for {@link TextModerationService}, per ISTQB CTFL v4.0.1 Section 2.2.1
 * (component testing). {@link GeminiClient}/{@link GeminiProperties} are mocked; a real {@link
 * ObjectMapper} (as a {@code @Spy}) is used since JSON parsing is exactly the behavior under
 * test — see {@code LocationResolutionServiceTest} for the same pattern.
 *
 * <p>The Error Guessing scenarios (Section 4.5.3) live here rather than in a separate class,
 * since {@code analyzeText}'s {@code catch (Exception e)} already treats every Gemini failure —
 * network outage, malformed response, whatever — identically: fall back to a neutral 0.5 score on
 * every attribute so the post is routed to manual review rather than silently auto-approved.
 * That single resilience contract is exactly what these tests lock in.
 */
@ExtendWith(MockitoExtension.class)
class TextModerationServiceTest {

  @Mock private GeminiClient geminiClient;
  @Mock private GeminiProperties geminiProperties;
  @Spy private ObjectMapper objectMapper = new ObjectMapper();

  @InjectMocks private TextModerationService textModerationService;

  @Nested
  @DisplayName("Skips the Gemini call entirely")
  class SkipTests {

    @Test
    @DisplayName("shouldReturnZeroScores_whenTextIsBlank")
    void shouldReturnZeroScores_whenTextIsBlank() {
      ModerationScores scores = textModerationService.analyzeText(1, 1, "   ");

      assertThat(scores.getHighestTextScore()).isZero();
    }

    @Test
    @DisplayName("shouldReturnZeroScores_whenApiKeyIsNotConfigured")
    void shouldReturnZeroScores_whenApiKeyIsNotConfigured() {
      when(geminiProperties.getApiKey()).thenReturn("  ");

      ModerationScores scores = textModerationService.analyzeText(1, 1, "hello world");

      assertThat(scores.getHighestTextScore()).isZero();
    }
  }

  @Nested
  @DisplayName("Happy path scoring")
  class HappyPathTests {

    @Test
    @DisplayName("shouldParseAllAttributeScores_fromAWellFormedGeminiResponse")
    void shouldParseAllAttributeScores_fromAWellFormedGeminiResponse() {
      when(geminiProperties.getApiKey()).thenReturn("a-configured-api-key");
      String response =
          """
          {
            "attributeScores": {
              "TOXICITY": { "summaryScore": { "value": 0.8 } },
              "SEVERE_TOXICITY": { "summaryScore": { "value": 0.1 } },
              "INSULT": { "summaryScore": { "value": 0.2 } },
              "THREAT": { "summaryScore": { "value": 0.05 } },
              "SEXUALLY_EXPLICIT": { "summaryScore": { "value": 0.0 } }
            }
          }
          """;
      when(geminiClient.generateContent(anyString())).thenReturn(response);

      ModerationScores scores = textModerationService.analyzeText(1, 1, "some toxic comment");

      assertThat(scores.getToxicity()).isEqualTo(0.8);
      assertThat(scores.getSevereToxicity()).isEqualTo(0.1);
      assertThat(scores.getInsult()).isEqualTo(0.2);
      assertThat(scores.getThreat()).isEqualTo(0.05);
      assertThat(scores.getSexuallyExplicit()).isEqualTo(0.0);
    }
  }

  @Nested
  @DisplayName("Error Guessing: Gemini outages and malformed responses")
  class ErrorGuessingTests {

    @Test
    @DisplayName("shouldFallBackToNeutralScores_whenGeminiClientThrows")
    void shouldFallBackToNeutralScores_whenGeminiClientThrows() {
      // Given — a network timeout, connection refused, or any other Gemini call failure
      when(geminiProperties.getApiKey()).thenReturn("a-configured-api-key");
      when(geminiClient.generateContent(anyString()))
          .thenThrow(new RuntimeException("Gemini API call failed"));

      // When / Then — never crash the moderation pipeline; fall back to neutral (0.5), not 0.0
      // (which would silently auto-approve) and not throwing (which would block the post flow)
      ModerationScores scores = textModerationService.analyzeText(1, 1, "some content");

      assertThat(scores.getToxicity()).isEqualTo(0.5);
      assertThat(scores.getSevereToxicity()).isEqualTo(0.5);
      assertThat(scores.getInsult()).isEqualTo(0.5);
      assertThat(scores.getThreat()).isEqualTo(0.5);
      assertThat(scores.getSexuallyExplicit()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("shouldFallBackToNeutralScores_whenGeminiResponseIsMalformedJson")
    void shouldFallBackToNeutralScores_whenGeminiResponseIsMalformedJson() {
      when(geminiProperties.getApiKey()).thenReturn("a-configured-api-key");
      when(geminiClient.generateContent(anyString())).thenReturn("not json at all");

      ModerationScores scores = textModerationService.analyzeText(1, 1, "some content");

      assertThat(scores.getHighestTextScore()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("shouldFallBackToNeutralScores_whenAttributeScoresIsMissing")
    void shouldFallBackToNeutralScores_whenAttributeScoresIsMissing() {
      when(geminiProperties.getApiKey()).thenReturn("a-configured-api-key");
      when(geminiClient.generateContent(anyString())).thenReturn("{}");

      ModerationScores scores = textModerationService.analyzeText(1, 1, "some content");

      assertThat(scores.getHighestTextScore()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("shouldDefaultToZero_forOneMalformedAttributeWithoutFailingTheOthers")
    void shouldDefaultToZero_forOneMalformedAttributeWithoutFailingTheOthers() {
      // Given — TOXICITY is missing its summaryScore entirely; the rest are well-formed
      when(geminiProperties.getApiKey()).thenReturn("a-configured-api-key");
      String response =
          """
          {
            "attributeScores": {
              "TOXICITY": { },
              "SEVERE_TOXICITY": { "summaryScore": { "value": 0.9 } },
              "INSULT": { "summaryScore": { "value": 0.1 } },
              "THREAT": { "summaryScore": { "value": 0.1 } },
              "SEXUALLY_EXPLICIT": { "summaryScore": { "value": 0.1 } }
            }
          }
          """;
      when(geminiClient.generateContent(anyString())).thenReturn(response);

      ModerationScores scores = textModerationService.analyzeText(1, 1, "some content");

      // Then — the one malformed attribute is isolated (defaults to 0.0), the rest still parse
      assertThat(scores.getToxicity()).isZero();
      assertThat(scores.getSevereToxicity()).isEqualTo(0.9);
    }
  }
}
