package com.socialapp.moderation.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import com.socialapp.moderation.config.ModerationProperties;
import com.socialapp.moderation.dto.ImageSafeSearchResult;
import com.socialapp.moderation.enums.Likelihood;

import reactor.core.publisher.Mono;

/**
 * Component (unit) tests for {@link ImageModerationService}, per ISTQB CTFL v4.0.1 Section 2.2.1
 * (component testing) and Section 4.5.3 (Error Guessing for the Cloud Vision outage scenarios).
 * {@link WebClient} is mocked — no real HTTP call to Cloud Vision is ever made.
 *
 * <p>Like {@code TextModerationService}, {@code analyzeImage}'s {@code catch (Exception e)}
 * already treats every Cloud Vision failure identically: fall back to {@link
 * ImageSafeSearchResult#pending()} rather than crashing the post-creation flow or silently
 * treating an unscored image as safe.
 */
@ExtendWith(MockitoExtension.class)
class ImageModerationServiceTest {

  private ModerationProperties properties;
  private WebClient webClient;
  private ImageModerationService imageModerationService;

  @BeforeEach
  void setUp() {
    properties = new ModerationProperties();
    properties.getCloudVision().setEnabled(true);
    properties.getCloudVision().setApiKey("a-configured-api-key");

    webClient = mock(WebClient.class);
    imageModerationService = new ImageModerationService(webClient, properties);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void stubCloudVisionResponse(Mono responseMono) {
    WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
    WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
    WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

    when(webClient.post()).thenReturn(uriSpec);
    when(uriSpec.uri(any(java.util.function.Function.class))).thenReturn(bodySpec);
    when(bodySpec.bodyValue(any())).thenReturn(headersSpec);
    when(headersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.bodyToMono(Map.class)).thenReturn(responseMono);
  }

  private static Map<String, Object> safeSearchResponse(
      String adult, String violence, String racy) {
    return Map.of(
        "responses",
        List.of(
            Map.of(
                "safeSearchAnnotation",
                Map.of("adult", adult, "violence", violence, "racy", racy))));
  }

  // =====================================================================
  // analyzeImage: skip conditions
  // =====================================================================

  @Nested
  @DisplayName("analyzeImage: skip conditions")
  class SkipTests {

    @Test
    @DisplayName("shouldReturnPending_whenApiKeyIsNotConfigured")
    void shouldReturnPending_whenApiKeyIsNotConfigured() {
      properties.getCloudVision().setApiKey("  ");

      ImageSafeSearchResult result =
          imageModerationService.analyzeImage(1, 1, "http://img.test/1.jpg");

      assertThat(result.getWorstLikelihood()).isEqualTo(Likelihood.UNKNOWN);
    }
  }

  // =====================================================================
  // analyzeImage: happy path
  // =====================================================================

  @Nested
  @DisplayName("analyzeImage: happy path")
  class HappyPathTests {

    @Test
    @DisplayName("shouldParseSafeSearchAnnotation_fromAWellFormedResponse")
    void shouldParseSafeSearchAnnotation_fromAWellFormedResponse() {
      stubCloudVisionResponse(Mono.just(safeSearchResponse("VERY_LIKELY", "UNLIKELY", "POSSIBLE")));

      ImageSafeSearchResult result =
          imageModerationService.analyzeImage(1, 1, "http://img.test/1.jpg");

      assertThat(result.getAdult()).isEqualTo(Likelihood.VERY_LIKELY);
      assertThat(result.getViolence()).isEqualTo(Likelihood.UNLIKELY);
      assertThat(result.getRacy()).isEqualTo(Likelihood.POSSIBLE);
      assertThat(result.getWorstLikelihood()).isEqualTo(Likelihood.VERY_LIKELY);
    }

    @Test
    @DisplayName("shouldDefaultToVeryUnlikely_whenAFieldIsMissingFromTheAnnotation")
    void shouldDefaultToVeryUnlikely_whenAFieldIsMissingFromTheAnnotation() {
      Map<String, Object> response =
          Map.of("responses", List.of(Map.of("safeSearchAnnotation", Map.of("adult", "LIKELY"))));
      stubCloudVisionResponse(Mono.just(response));

      ImageSafeSearchResult result =
          imageModerationService.analyzeImage(1, 1, "http://img.test/1.jpg");

      assertThat(result.getAdult()).isEqualTo(Likelihood.LIKELY);
      assertThat(result.getViolence()).isEqualTo(Likelihood.VERY_UNLIKELY);
    }

    @Test
    @DisplayName("shouldFallBackToUnknown_whenLikelihoodStringIsNotRecognized")
    void shouldFallBackToUnknown_whenLikelihoodStringIsNotRecognized() {
      stubCloudVisionResponse(
          Mono.just(safeSearchResponse("NOT_A_REAL_LIKELIHOOD", "VERY_UNLIKELY", "VERY_UNLIKELY")));

      ImageSafeSearchResult result =
          imageModerationService.analyzeImage(1, 1, "http://img.test/1.jpg");

      assertThat(result.getAdult()).isEqualTo(Likelihood.UNKNOWN);
    }
  }

  // =====================================================================
  // Error Guessing: Cloud Vision outages and malformed responses
  // =====================================================================

  @Nested
  @DisplayName("Error Guessing: Cloud Vision outages and malformed responses")
  class ErrorGuessingTests {

    @Test
    @DisplayName("shouldReturnPending_whenCloudVisionTimesOut")
    void shouldReturnPending_whenCloudVisionTimesOut() {
      stubCloudVisionResponse(Mono.error(new SocketTimeoutException("Read timed out")));

      ImageSafeSearchResult result =
          imageModerationService.analyzeImage(1, 1, "http://img.test/1.jpg");

      assertThat(result.getWorstLikelihood()).isEqualTo(Likelihood.UNKNOWN);
    }

    @Test
    @DisplayName("shouldReturnPending_whenConnectionIsRefused")
    void shouldReturnPending_whenConnectionIsRefused() {
      stubCloudVisionResponse(Mono.error(new ConnectException("Connection refused")));

      ImageSafeSearchResult result =
          imageModerationService.analyzeImage(1, 1, "http://img.test/1.jpg");

      assertThat(result.getWorstLikelihood()).isEqualTo(Likelihood.UNKNOWN);
    }

    @Test
    @DisplayName("shouldReturnPending_whenResponseIsNull")
    void shouldReturnPending_whenResponseIsNull() {
      stubCloudVisionResponse(Mono.empty());

      ImageSafeSearchResult result =
          imageModerationService.analyzeImage(1, 1, "http://img.test/1.jpg");

      assertThat(result.getWorstLikelihood()).isEqualTo(Likelihood.UNKNOWN);
    }

    @Test
    @DisplayName("shouldReturnPending_whenResponsesListIsMissing")
    void shouldReturnPending_whenResponsesListIsMissing() {
      stubCloudVisionResponse(Mono.just(Map.of()));

      ImageSafeSearchResult result =
          imageModerationService.analyzeImage(1, 1, "http://img.test/1.jpg");

      assertThat(result.getWorstLikelihood()).isEqualTo(Likelihood.UNKNOWN);
    }

    @Test
    @DisplayName("shouldReturnPending_whenSafeSearchAnnotationIsMissing")
    void shouldReturnPending_whenSafeSearchAnnotationIsMissing() {
      stubCloudVisionResponse(Mono.just(Map.of("responses", List.of(Map.of()))));

      ImageSafeSearchResult result =
          imageModerationService.analyzeImage(1, 1, "http://img.test/1.jpg");

      assertThat(result.getWorstLikelihood()).isEqualTo(Likelihood.UNKNOWN);
    }
  }

  // =====================================================================
  // analyzeImages: batch behavior
  // =====================================================================

  @Nested
  @DisplayName("analyzeImages")
  class AnalyzeImagesTests {

    @Test
    @DisplayName("shouldReturnSafe_whenCloudVisionIsDisabled")
    void shouldReturnSafe_whenCloudVisionIsDisabled() {
      properties.getCloudVision().setEnabled(false);

      ImageSafeSearchResult result =
          imageModerationService.analyzeImages(1, 1, List.of("http://img.test/1.jpg"));

      assertThat(result.getWorstLikelihood()).isEqualTo(Likelihood.VERY_UNLIKELY);
    }

    @Test
    @DisplayName("shouldReturnSafe_whenImageListIsEmpty")
    void shouldReturnSafe_whenImageListIsEmpty() {
      ImageSafeSearchResult result = imageModerationService.analyzeImages(1, 1, List.of());

      assertThat(result.getWorstLikelihood()).isEqualTo(Likelihood.VERY_UNLIKELY);
    }

    @Test
    @DisplayName("shouldReturnSafe_whenImageListIsNull")
    void shouldReturnSafe_whenImageListIsNull() {
      ImageSafeSearchResult result = imageModerationService.analyzeImages(1, 1, null);

      assertThat(result.getWorstLikelihood()).isEqualTo(Likelihood.VERY_UNLIKELY);
    }

    @Test
    @DisplayName("shouldReturnTheWorstResult_acrossMultipleImages")
    void shouldReturnTheWorstResult_acrossMultipleImages() {
      // Given — first image is safe, second is not; stub always returns the same "worse" result
      // since the mock chain is call-order-agnostic here (both images hit the same mocked chain)
      stubCloudVisionResponse(
          Mono.just(safeSearchResponse("VERY_UNLIKELY", "POSSIBLE", "VERY_UNLIKELY")));

      ImageSafeSearchResult result =
          imageModerationService.analyzeImages(
              1, 1, List.of("http://img.test/1.jpg", "http://img.test/2.jpg"));

      assertThat(result.getWorstLikelihood()).isEqualTo(Likelihood.POSSIBLE);
    }

    @Test
    @DisplayName("shouldStopEarly_onceAnImageIsAlreadyRejected")
    void shouldStopEarly_onceAnImageIsAlreadyRejected() {
      // Given — default rejectLikelihood is LIKELY; first image already meets it
      stubCloudVisionResponse(
          Mono.just(safeSearchResponse("VERY_LIKELY", "VERY_UNLIKELY", "VERY_UNLIKELY")));

      ImageSafeSearchResult result =
          imageModerationService.analyzeImages(
              1,
              1,
              List.of("http://img.test/1.jpg", "http://img.test/2.jpg", "http://img.test/3.jpg"));

      assertThat(result.getWorstLikelihood()).isEqualTo(Likelihood.VERY_LIKELY);
      // Only the first image should have been called before short-circuiting
      verify(webClient, times(1)).post();
    }
  }
}
