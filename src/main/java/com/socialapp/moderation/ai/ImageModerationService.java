package com.socialapp.moderation.ai;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.socialapp.moderation.config.ModerationProperties;
import com.socialapp.moderation.dto.ImageSafeSearchResult;
import com.socialapp.moderation.enums.Likelihood;

import io.jsonwebtoken.lang.Strings;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ImageModerationService {
  private final WebClient cloudVisionWebClient;
  private final ModerationProperties properties;

  public ImageModerationService(
      @Qualifier("cloudVisionWebClient") WebClient cloudVisionWebClient,
      ModerationProperties properties) {
    this.cloudVisionWebClient = cloudVisionWebClient;
    this.properties = properties;
  }

  public ImageSafeSearchResult analyzeImage(Integer postId, Integer authorId, String imageUrl) {
    if (!Strings.hasText(properties.getCloudVision().getApiKey())) {
      log.warn(
          "[postId={}, authorId={}] Cloud Vision API key is not configured, skipping image"
              + " moderation for {} (treated as no signal, not rejected)",
          postId,
          authorId,
          imageUrl);
      return ImageSafeSearchResult.pending();
    }

    try {
      Map<String, Object> requestBody = buildAnnotateRequest(imageUrl);

      @SuppressWarnings("unchecked")
      Map<String, Object> response =
          cloudVisionWebClient
              .post()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/images:annotate")
                          .queryParam("key", properties.getCloudVision().getApiKey())
                          .build())
              .bodyValue(requestBody)
              .retrieve()
              .bodyToMono(Map.class)
              .block();

      ImageSafeSearchResult result = parseResponse(response);
      log.info(
          "[postId={}, authorId={}] Cloud Vision result for {}: worst={}",
          postId,
          authorId,
          imageUrl,
          result.getWorstLikelihood());
      return result;
    } catch (Exception e) {
      log.error(
          "[postId={}, authorId={}] Failed to call Cloud Vision API for image {}: {}",
          postId,
          authorId,
          imageUrl,
          e.getMessage());
      return ImageSafeSearchResult.pending();
    }
  }

  public ImageSafeSearchResult analyzeImages(
      Integer postId, Integer authorId, List<String> imageUrls) {
    if (!properties.getCloudVision().isEnabled()) {
      log.debug(
          "[postId={}, authorId={}] Cloud Vision image moderation is disabled, skipping",
          postId,
          authorId);
      return ImageSafeSearchResult.safe();
    }

    if (imageUrls == null || imageUrls.isEmpty()) {
      log.info("[postId={}, authorId={}] Skipping image moderation: no images", postId, authorId);
      return ImageSafeSearchResult.safe();
    }

    ImageSafeSearchResult worstResult = ImageSafeSearchResult.safe();

    for (String imageUrl : imageUrls) {
      ImageSafeSearchResult result = analyzeImage(postId, authorId, imageUrl);
      if (result.getWorstLikelihood().getScore() > worstResult.getWorstLikelihood().getScore()) {
        worstResult = result;
      }
      if (worstResult.isRejected(properties.getCloudVision().getRejectLikelihood())) {
        break;
      }
    }

    return worstResult;
  }

  private Map<String, Object> buildAnnotateRequest(String imageUrl) {
    return Map.of(
        "requests",
        List.of(
            Map.of(
                "image", Map.of("source", Map.of("imageUri", imageUrl)),
                "features", List.of(Map.of("type", "SAFE_SEARCH_DETECTION")))));
  }

  @SuppressWarnings("unchecked")
  private ImageSafeSearchResult parseResponse(Map<String, Object> response) {
    try {
      if (Objects.isNull(response)) {
        return ImageSafeSearchResult.pending();
      }

      List<Map<String, Object>> responses = (List<Map<String, Object>>) response.get("responses");
      if (Objects.isNull(responses) || responses.isEmpty()) {
        return ImageSafeSearchResult.pending();
      }

      Map<String, Object> firstResponse = responses.get(0);
      Map<String, Object> safeSearch =
          (Map<String, Object>) firstResponse.get("safeSearchAnnotation");
      if (Objects.isNull(safeSearch)) {
        return ImageSafeSearchResult.pending();
      }

      Likelihood adult =
          Likelihood.fromString((String) safeSearch.getOrDefault("adult", "VERY_UNLIKELY"));
      Likelihood violence =
          Likelihood.fromString((String) safeSearch.getOrDefault("violence", "VERY_UNLIKELY"));
      Likelihood racy =
          Likelihood.fromString((String) safeSearch.getOrDefault("racy", "VERY_UNLIKELY"));

      return new ImageSafeSearchResult(adult, violence, racy);
    } catch (Exception e) {
      log.error("Failed to parse Cloud Vision response", e);
      return ImageSafeSearchResult.pending();
    }
  }
}
