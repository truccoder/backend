package com.socialapp.posts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.knowledge.client.GeminiClient;
import com.socialapp.knowledge.config.GeminiProperties;
import com.socialapp.posts.dto.LocationResolutionRequestDto;
import com.socialapp.posts.dto.LocationResolutionResponseDto;
import com.socialapp.posts.entity.enums.LocationType;

/**
 * Component (unit) tests for {@link LocationResolutionService}, per ISTQB CTFL v4.0.1 Section
 * 2.2.1 (component testing) — {@link GeminiClient} and {@link GeminiProperties} are mocked; a
 * real {@link ObjectMapper} is used since JSON parsing is exactly the behavior under test. BDD
 * Given/When/Then per Section 2.1.3.
 */
@ExtendWith(MockitoExtension.class)
class LocationResolutionServiceTest {

  @Mock private GeminiClient geminiClient;
  @Mock private GeminiProperties geminiProperties;
  @Spy private ObjectMapper objectMapper = new ObjectMapper();

  @InjectMocks private LocationResolutionService locationResolutionService;

  @BeforeEach
  void setUpDefaultApiKey() {
    lenient().when(geminiProperties.getApiKey()).thenReturn("a-configured-api-key");
  }

  // =====================================================================
  // resolve: input validation
  // =====================================================================

  @Nested
  @DisplayName("resolve: input validation")
  class InputValidationTests {

    @Test
    @DisplayName("should throw ValidationException when neither query nor coordinates are given")
    void shouldThrowValidationException_whenNeitherQueryNorCoordinatesAreProvided() {
      // Given
      LocationResolutionRequestDto request = new LocationResolutionRequestDto(null, null, null);

      // When / Then
      assertThatThrownBy(() -> locationResolutionService.resolve(request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Either query or both latitude and longitude are required");
    }

    @Test
    @DisplayName("should throw ValidationException when only latitude is given, not longitude")
    void shouldThrowValidationException_whenOnlyLatitudeIsProvided() {
      // Given
      LocationResolutionRequestDto request = new LocationResolutionRequestDto(null, 21.0285, null);

      // When / Then
      assertThatThrownBy(() -> locationResolutionService.resolve(request))
          .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("should throw ValidationException when the Gemini API key is not configured")
    void shouldThrowValidationException_whenGeminiApiKeyIsBlank() {
      // Given
      when(geminiProperties.getApiKey()).thenReturn("  ");
      LocationResolutionRequestDto request = new LocationResolutionRequestDto("Hanoi", null, null);

      // When / Then
      assertThatThrownBy(() -> locationResolutionService.resolve(request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("not available right now");
    }
  }

  // =====================================================================
  // resolve: reverse geocoding (coordinates provided)
  // =====================================================================

  @Nested
  @DisplayName("resolve: reverse geocoding")
  class ReverseGeocodeTests {

    @Test
    @DisplayName("should return a COORDINATE result and override Gemini's echoed lat/lng")
    void shouldReturnCoordinateResult_andOverrideEchoedCoordinates() {
      // Given — Gemini echoes back slightly different coordinates than the caller's ground truth
      String geminiResponse =
          """
          {
            "locationDetails": {
              "display_name": "District 1, Ho Chi Minh City, Vietnam",
              "latitude": 10.7,
              "longitude": 106.6,
              "city": "Ho Chi Minh City",
              "country": "Vietnam"
            }
          }
          """;
      when(geminiClient.generateContent(anyString())).thenReturn(geminiResponse);
      LocationResolutionRequestDto request =
          new LocationResolutionRequestDto(null, 10.7769, 106.7009);

      // When
      List<LocationResolutionResponseDto> results = locationResolutionService.resolve(request);

      // Then
      assertThat(results).hasSize(1);
      LocationResolutionResponseDto result = results.get(0);
      assertThat(result.locationType()).isEqualTo(LocationType.COORDINATE);
      assertThat(result.googlePlaceId()).startsWith("gemini:");
      assertThat(result.locationDetails().getDisplayName())
          .isEqualTo("District 1, Ho Chi Minh City, Vietnam");
      assertThat(result.locationDetails().getLatitude()).isEqualTo(10.7769);
      assertThat(result.locationDetails().getLongitude()).isEqualTo(106.7009);
      assertThat(result.googleMapsUrl()).contains("10.7769").contains("106.7009");
    }

    @Test
    @DisplayName("should throw ValidationException when Gemini's response has no locationDetails")
    void shouldThrowValidationException_whenLocationDetailsMissing() {
      // Given
      when(geminiClient.generateContent(anyString())).thenReturn("{}");
      LocationResolutionRequestDto request =
          new LocationResolutionRequestDto(null, 10.7769, 106.7009);

      // When / Then
      assertThatThrownBy(() -> locationResolutionService.resolve(request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Could not resolve location for the given coordinates");
    }

    @Test
    @DisplayName("should throw ValidationException when Gemini's response is not valid JSON")
    void shouldThrowValidationException_whenGeminiResponseIsMalformedJson() {
      // Given
      when(geminiClient.generateContent(anyString())).thenReturn("not json at all");
      LocationResolutionRequestDto request =
          new LocationResolutionRequestDto(null, 10.7769, 106.7009);

      // When / Then
      assertThatThrownBy(() -> locationResolutionService.resolve(request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Could not resolve location for the given coordinates");
    }

    @Test
    @DisplayName("should throw ValidationException when the Gemini client itself fails")
    void shouldThrowValidationException_whenGeminiClientThrows() {
      // Given
      when(geminiClient.generateContent(anyString()))
          .thenThrow(new RuntimeException("Gemini API call failed"));
      LocationResolutionRequestDto request =
          new LocationResolutionRequestDto(null, 10.7769, 106.7009);

      // When / Then
      assertThatThrownBy(() -> locationResolutionService.resolve(request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Could not resolve location for the given coordinates");
    }
  }

  // =====================================================================
  // resolve: forward search (free-text query)
  // =====================================================================

  @Nested
  @DisplayName("resolve: forward search")
  class ForwardSearchTests {

    @Test
    @DisplayName("should return every well-formed candidate, mapped to the response shape")
    void shouldReturnEveryWellFormedCandidate() {
      // Given
      String geminiResponse =
          """
          {
            "candidates": [
              {
                "locationType": "PLACE",
                "locationDetails": {
                  "display_name": "Hoan Kiem Lake",
                  "latitude": 21.0285,
                  "longitude": 105.8542,
                  "city": "Hanoi",
                  "country": "Vietnam"
                }
              },
              {
                "locationType": "REGION",
                "locationDetails": {
                  "display_name": "Hanoi, Vietnam",
                  "latitude": 21.0278,
                  "longitude": 105.8342,
                  "city": "Hanoi",
                  "country": "Vietnam"
                }
              }
            ]
          }
          """;
      when(geminiClient.generateContent(anyString())).thenReturn(geminiResponse);
      LocationResolutionRequestDto request = new LocationResolutionRequestDto("Hanoi", null, null);

      // When
      List<LocationResolutionResponseDto> results = locationResolutionService.resolve(request);

      // Then
      assertThat(results).hasSize(2);
      assertThat(results.get(0).locationType()).isEqualTo(LocationType.PLACE);
      assertThat(results.get(0).locationDetails().getDisplayName()).isEqualTo("Hoan Kiem Lake");
      assertThat(results.get(1).locationType()).isEqualTo(LocationType.REGION);
    }

    @Test
    @DisplayName("should skip malformed candidates but keep the well-formed ones")
    void shouldSkipMalformedCandidates_butKeepWellFormedOnes() {
      // Given — first candidate is missing locationDetails entirely
      String geminiResponse =
          """
          {
            "candidates": [
              { "locationType": "PLACE" },
              {
                "locationType": "PLACE",
                "locationDetails": {
                  "display_name": "Hoan Kiem Lake",
                  "latitude": 21.0285,
                  "longitude": 105.8542,
                  "city": "Hanoi",
                  "country": "Vietnam"
                }
              }
            ]
          }
          """;
      when(geminiClient.generateContent(anyString())).thenReturn(geminiResponse);
      LocationResolutionRequestDto request = new LocationResolutionRequestDto("Hanoi", null, null);

      // When
      List<LocationResolutionResponseDto> results = locationResolutionService.resolve(request);

      // Then
      assertThat(results).hasSize(1);
      assertThat(results.get(0).locationDetails().getDisplayName()).isEqualTo("Hoan Kiem Lake");
    }

    @Test
    @DisplayName("should skip a candidate whose locationType is not a recognized enum value")
    void shouldSkipCandidate_whenLocationTypeIsUnrecognized() {
      // Given
      String geminiResponse =
          """
          {
            "candidates": [
              {
                "locationType": "GALAXY",
                "locationDetails": {
                  "display_name": "Nowhere",
                  "latitude": 0,
                  "longitude": 0,
                  "city": null,
                  "country": null
                }
              }
            ]
          }
          """;
      when(geminiClient.generateContent(anyString())).thenReturn(geminiResponse);
      LocationResolutionRequestDto request =
          new LocationResolutionRequestDto("asdkfjhalksdjf", null, null);

      // When / Then — every candidate fails to parse, so the results list ends up empty
      assertThatThrownBy(() -> locationResolutionService.resolve(request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Could not resolve location: asdkfjhalksdjf");
    }

    @Test
    @DisplayName("should throw ValidationException when Gemini returns no candidates at all")
    void shouldThrowValidationException_whenCandidatesAreMissing() {
      // Given
      when(geminiClient.generateContent(anyString())).thenReturn("{}");
      LocationResolutionRequestDto request =
          new LocationResolutionRequestDto("asdkfjhalksdjf", null, null);

      // When / Then
      assertThatThrownBy(() -> locationResolutionService.resolve(request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Could not resolve location: asdkfjhalksdjf");
    }

    @Test
    @DisplayName("should throw ValidationException when Gemini returns an empty candidates list")
    void shouldThrowValidationException_whenCandidatesListIsEmpty() {
      // Given
      when(geminiClient.generateContent(anyString())).thenReturn("{ \"candidates\": [] }");
      LocationResolutionRequestDto request = new LocationResolutionRequestDto("??", null, null);

      // When / Then
      assertThatThrownBy(() -> locationResolutionService.resolve(request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Could not resolve location: ??");
    }

    @Test
    @DisplayName("should throw ValidationException when the Gemini client itself fails")
    void shouldThrowValidationException_whenGeminiClientThrows() {
      // Given
      when(geminiClient.generateContent(anyString()))
          .thenThrow(new RuntimeException("Gemini API call failed"));
      LocationResolutionRequestDto request = new LocationResolutionRequestDto("Hanoi", null, null);

      // When / Then
      assertThatThrownBy(() -> locationResolutionService.resolve(request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Could not resolve location: Hanoi");
    }
  }
}
