package com.socialapp.knowledge.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.common.exception.ExternalApiException;
import com.socialapp.knowledge.config.GeminiProperties;

import reactor.core.publisher.Mono;

/**
 * Component (unit) tests for {@link GeminiClient}, per ISTQB CTFL v4.0.1 Section 2.2.1 (component
 * testing) and Section 4.3.2 (branch testing over each "did Gemini return the expected shape"
 * check) — mirrors {@code GithubApiClientTest}'s pattern of mocking {@link WebClient}'s fluent
 * chain with a real {@link ObjectMapper}, not a mocked one, so {@link
 * com.fasterxml.jackson.databind.JsonNode} navigation behaves exactly as in production.
 *
 * <p>{@link #shouldPropagateSpecificMessage_whenNoCandidatesInResponse()} and {@link
 * #shouldPropagateSpecificMessage_whenNoTextContentInResponse()} exist specifically to lock in a
 * fix: both {@code generateContent} and {@code extractTextFromResponse} used to have a {@code
 * catch (Exception e)} broad enough to re-catch the {@link ExternalApiException} they had just
 * thrown themselves, re-wrapping a specific reason ("No candidates...") under a generic one
 * ("Failed to generate content from Gemini"). The fix rethrows an already-thrown {@link
 * ExternalApiException} as-is before falling through to the generic wrap — these tests assert the
 * exact message (not just {@code hasMessageContaining}) to fail loudly if that shadowing ever
 * comes back.
 */
class GeminiClientTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private GeminiProperties properties;
  private WebClient webClient;
  private GeminiClient geminiClient;

  @BeforeEach
  void setUp() {
    properties = new GeminiProperties();
    properties.setApiKey("test-api-key");
    properties.setModel("gemini-test-model");
    properties.setTemperature(0.5);
    properties.setMaxOutputTokens(1024);

    webClient = mock(WebClient.class);
    geminiClient = new GeminiClient(webClient, properties, objectMapper);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private ArgumentCaptor<Map> stubGenerateContent(Mono<String> responseMono) {
    WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
    WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
    WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);

    when(webClient.post()).thenReturn(uriSpec);
    when(uriSpec.uri(any(Function.class)))
        .thenAnswer(
            invocation -> {
              Function<UriBuilder, URI> uriFunction = invocation.getArgument(0);
              uriFunction.apply(UriComponentsBuilder.fromUriString("http://gemini.test"));
              return bodySpec;
            });
    when(bodySpec.bodyValue(bodyCaptor.capture())).thenReturn(headersSpec);
    when(headersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.bodyToMono(String.class)).thenReturn(responseMono);
    return bodyCaptor;
  }

  private static String geminiResponseJson(String text) {
    return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + text + "\"}]}}]}";
  }

  // =====================================================================
  // generateContent
  // =====================================================================

  @Nested
  @DisplayName("generateContent")
  class GenerateContentTests {

    @Test
    @DisplayName("should return the extracted text and send prompt/temperature/maxOutputTokens")
    void shouldReturnExtractedText_whenResponseHasValidCandidate() {
      // Given
      ArgumentCaptor<Map> bodyCaptor = stubGenerateContent(Mono.just(geminiResponseJson("hi")));

      // When
      String result = geminiClient.generateContent("say hi");

      // Then
      assertThat(result).isEqualTo("hi");
      Map<String, Object> body = bodyCaptor.getValue();
      List<Map<String, Object>> contents = (List<Map<String, Object>>) body.get("contents");
      List<Map<String, Object>> parts = (List<Map<String, Object>>) contents.get(0).get("parts");
      assertThat(parts.get(0)).containsEntry("text", "say hi");
      Map<String, Object> generationConfig = (Map<String, Object>) body.get("generationConfig");
      assertThat(generationConfig)
          .containsEntry("temperature", 0.5)
          .containsEntry("maxOutputTokens", 1024);
    }

    @Test
    @DisplayName("should wrap an unexpected WebClient failure under a generic message")
    void shouldWrapWebClientFailure_underGenericMessage() {
      // Given
      stubGenerateContent(Mono.error(new IllegalStateException("connection refused")));

      // When / Then
      assertThatThrownBy(() -> geminiClient.generateContent("prompt"))
          .isInstanceOf(ExternalApiException.class)
          .hasMessage("Failed to generate content from Gemini")
          .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("should propagate the specific \"no candidates\" message, not a generic wrapper")
    void shouldPropagateSpecificMessage_whenNoCandidatesInResponse() {
      // Given
      stubGenerateContent(Mono.just("{\"candidates\":[]}"));

      // When / Then: exact message (not hasMessageContaining) — fails if the shadowing bug returns
      assertThatThrownBy(() -> geminiClient.generateContent("prompt"))
          .isInstanceOf(ExternalApiException.class)
          .hasMessage("No candidates in Gemini response");
    }

    @Test
    @DisplayName("should propagate the specific \"no text content\" message, not a generic wrapper")
    void shouldPropagateSpecificMessage_whenNoTextContentInResponse() {
      // Given
      stubGenerateContent(Mono.just("{\"candidates\":[{\"content\":{\"parts\":[{}]}}]}"));

      // When / Then
      assertThatThrownBy(() -> geminiClient.generateContent("prompt"))
          .isInstanceOf(ExternalApiException.class)
          .hasMessage("No text content in Gemini response");
    }

    @Test
    @DisplayName("should wrap malformed (non-JSON) response bodies as a parse failure")
    void shouldWrapParseFailure_whenResponseIsNotValidJson() {
      // Given
      stubGenerateContent(Mono.just("not-json"));

      // When / Then
      assertThatThrownBy(() -> geminiClient.generateContent("prompt"))
          .isInstanceOf(ExternalApiException.class)
          .hasMessage("Failed to parse Gemini response");
    }

    @Test
    @DisplayName("should wrap an unexpected shape (empty parts array) as a parse failure")
    void shouldWrapParseFailure_whenPartsArrayIsEmpty() {
      // Given: candidates[0].content.parts is present but empty, so .get(0) on it returns null and
      // the next .path("text") NPEs — exercises the safety-net catch, not the two explicit checks.
      stubGenerateContent(Mono.just("{\"candidates\":[{\"content\":{\"parts\":[]}}]}"));

      // When / Then
      assertThatThrownBy(() -> geminiClient.generateContent("prompt"))
          .isInstanceOf(ExternalApiException.class)
          .hasMessage("Failed to parse Gemini response")
          .hasCauseInstanceOf(NullPointerException.class);
    }
  }
}
