package com.socialapp.knowledge.client;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    } catch (Exception e) {
      log.error("Gemini API call failed: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to generate content from Gemini", e);
    }
  }

  private String extractTextFromResponse(String response) {
    try {
      JsonNode root = objectMapper.readTree(response);
      JsonNode candidates = root.path("candidates");
      if (candidates.isEmpty()) {
        throw new RuntimeException("No candidates in Gemini response");
      }
      JsonNode textNode = candidates.get(0).path("content").path("parts").get(0).path("text");
      if (Objects.isNull(textNode) || textNode.isMissingNode()) {
        throw new RuntimeException("No text content in Gemini response");
      }
      return textNode.asText();
    } catch (Exception e) {
      log.error("Failed to parse Gemini response: {}", response, e);
      throw new RuntimeException("Failed to parse Gemini response", e);
    }
  }
}
