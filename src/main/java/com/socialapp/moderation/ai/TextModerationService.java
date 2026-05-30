package com.socialapp.moderation.ai;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.socialapp.moderation.config.ModerationProperties;
import com.socialapp.moderation.dto.ModerationScores;
import com.socialapp.moderation.enums.PerspectiveAttribute;

import io.jsonwebtoken.lang.Strings;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class TextModerationService {
  private final WebClient perspectiveApiWebClient;
  private final ModerationProperties properties;

  public TextModerationService(
      @Qualifier("perspectiveApiWebClient") WebClient perspectiveApiWebClient,
      ModerationProperties properties) {
    this.perspectiveApiWebClient = perspectiveApiWebClient;
    this.properties = properties;
  }

  @SuppressWarnings("unchecked")
  public ModerationScores analyzeText(String text) {
    if (!Strings.hasText(text)) {
      return ModerationScores.builder().build();
    }

    try {
      Map<String, Object> requestedAttributes =
          Arrays.stream(PerspectiveAttribute.values())
              .collect(Collectors.toMap(Enum::name, attr -> Map.of()));

      Map<String, Object> requestBody =
          Map.of(
              "comment",
              Map.of("text", text),
              "languages",
              new String[] {"vi", "en"},
              "requestedAttributes",
              requestedAttributes);

      Map<String, Object> response =
          perspectiveApiWebClient
              .post()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/comments:analyze")
                          .queryParam("key", properties.getPerspectiveApi().getKey())
                          .build())
              .bodyValue(requestBody)
              .retrieve()
              .bodyToMono(Map.class)
              .block();

      if (Objects.isNull(response)) {
        log.warn("Perspective API returned null response");
        return buildDefaultScores();
      }

      Map<String, Object> attributeScores = (Map<String, Object>) response.get("attributeScores");

      return ModerationScores.builder()
          .toxicity(extractScore(attributeScores, PerspectiveAttribute.TOXICITY))
          .severeToxicity(extractScore(attributeScores, PerspectiveAttribute.SEVERE_TOXICITY))
          .insult(extractScore(attributeScores, PerspectiveAttribute.INSULT))
          .threat(extractScore(attributeScores, PerspectiveAttribute.THREAT))
          .sexuallyExplicit(extractScore(attributeScores, PerspectiveAttribute.SEXUALLY_EXPLICIT))
          .build();
    } catch (Exception e) {
      log.error("Failed to call Perspective API: {}", e.getMessage());
      return buildDefaultScores();
    }
  }

  @SuppressWarnings("unchecked")
  private double extractScore(Map<String, Object> attributeScores, PerspectiveAttribute attribute) {
    try {
      Map<String, Object> attrData = (Map<String, Object>) attributeScores.get(attribute.name());
      Map<String, Object> summaryScore = (Map<String, Object>) attrData.get("summaryScore");
      Number value = (Number) summaryScore.get("value");
      return value.doubleValue();
    } catch (Exception e) {
      return 0.0;
    }
  }

  private ModerationScores buildDefaultScores() {
    return ModerationScores.builder()
        .toxicity(0.5)
        .severeToxicity(0.5)
        .insult(0.5)
        .threat(0.5)
        .sexuallyExplicit(0.5)
        .build();
  }
}
