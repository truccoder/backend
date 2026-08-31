package com.socialapp.knowledge.client;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.common.exception.ExternalApiException;
import com.socialapp.common.exception.ExternalRateLimitException;
import com.socialapp.knowledge.config.GeminiProperties;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class GeminiClient {
  private final WebClient geminiWebClient;
  private final GeminiProperties properties;
  private final ObjectMapper objectMapper;

  public GeminiClient(
      @Qualifier("geminiWebClient") WebClient geminiWebClient,
      GeminiProperties properties,
      ObjectMapper objectMapper) {
    this.geminiWebClient = geminiWebClient;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  public String generateContent(String prompt) {
    Map<String, Object> requestBody =
        Map.of(
            "contents",
            List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
            "generationConfig",
            Map.of(
                "temperature", properties.getTemperature(),
                "maxOutputTokens", properties.getMaxOutputTokens(),
                "responseMimeType", "application/json"));

    try {
      String response =
          geminiWebClient
              .post()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/models/{model}:generateContent")
                          .queryParam("key", properties.getApiKey())
                          .build(properties.getModel()))
              .bodyValue(requestBody)
              .retrieve()
              .bodyToMono(String.class)
              .block();

      return extractTextFromResponse(response);
    } catch (ExternalApiException e) {
      // Already carries the specific reason (no candidates / no text / bad JSON) — rethrow as-is
      // instead of shadowing it under a generic message.
      throw e;
    } catch (WebClientResponseException e) {
      // Gemini answered with an HTTP error status. Log the status and body — this is the only
      // place they exist, and backend-plan B32 is stuck for want of them ("is the endpoint dying
      // on RESOURCE_EXHAUSTED or on UNAVAILABLE?"). Then split the one status the caller must
      // treat differently:
      //
      //  - 429 → we are over Gemini's quota / rate limit. Surfaced as 429 so the caller stops
      //    retrying (every free-tier key in this project shares one RPD budget: explain, location
      //    resolve, trending classification, moderation).
      //  - anything else (5xx, an expired key's 400, a safety refusal) → 503, transient-looking,
      //    retry allowed. Same as before this branch existed.
      log.warn(
          "Gemini generateContent failed: HTTP {} — {}",
          e.getStatusCode(),
          e.getResponseBodyAsString());
      if (e.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
        throw new ExternalRateLimitException("Gemini quota or rate limit exceeded", e);
      }
      throw new ExternalApiException("Failed to generate content from Gemini", e);
    } catch (Exception e) {
      throw new ExternalApiException("Failed to generate content from Gemini", e);
    }
  }

  private String extractTextFromResponse(String response) {
    try {
      JsonNode root = objectMapper.readTree(response);
      JsonNode candidates = root.path("candidates");
      if (candidates.isEmpty()) {
        throw new ExternalApiException("No candidates in Gemini response");
      }
      JsonNode textNode = candidates.get(0).path("content").path("parts").get(0).path("text");
      if (Objects.isNull(textNode) || textNode.isMissingNode()) {
        throw new ExternalApiException("No text content in Gemini response");
      }
      return textNode.asText();
    } catch (ExternalApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ExternalApiException("Failed to parse Gemini response", e);
    }
  }
}
