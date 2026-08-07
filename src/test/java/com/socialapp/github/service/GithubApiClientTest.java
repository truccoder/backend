package com.socialapp.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.common.exception.ExternalApiException;

import reactor.core.publisher.Mono;

/**
 * Component (unit) tests for {@link GithubApiClient}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing; Section 4.3.2 branch testing over each method's "did GitHub return the
 * expected shape" check). {@link WebClient} is mocked — no real HTTP call to GitHub is ever made.
 * A real {@link ObjectMapper} is used (not mocked) so the {@code createArrayNode}/{@code
 * createObjectNode} fallbacks and {@link JsonNode} navigation behave exactly as in production.
 */
class GithubApiClientTest {

  private static final String ACCESS_TOKEN = "gho_test-access-token";
  private static final String USERNAME = "octocat";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private WebClient webClient;
  private GithubApiClient githubApiClient;

  @BeforeEach
  void setUp() {
    webClient = mock(WebClient.class);
    WebClient.Builder builder = mock(WebClient.Builder.class);
    when(builder.build()).thenReturn(webClient);

    githubApiClient = new GithubApiClient(builder, objectMapper);
    ReflectionTestUtils.setField(githubApiClient, "clientId", "test-client-id");
    ReflectionTestUtils.setField(githubApiClient, "clientSecret", "test-client-secret");
    ReflectionTestUtils.setField(githubApiClient, "redirectUri", "https://app.test/oauth/callback");
    // The link flow has its own callback: a GitHub code is single-use, so sharing one route
    // meant the login handler spent the code the link flow needed (B23a).
    ReflectionTestUtils.setField(
        githubApiClient, "linkRedirectUri", "https://app.test/settings/github/callback");
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void stubExchangeCodeForToken(Mono<JsonNode> responseMono) {
    WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
    WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
    WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

    when(webClient.post()).thenReturn(uriSpec);
    when(uriSpec.uri(anyString())).thenReturn(bodySpec);
    when(bodySpec.header(any(), any())).thenReturn(bodySpec);
    when(bodySpec.contentType(any())).thenReturn(bodySpec);
    when(bodySpec.bodyValue(any())).thenReturn(headersSpec);
    when(headersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.bodyToMono(JsonNode.class)).thenReturn(responseMono);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void stubGraphQlPost(Mono<JsonNode> responseMono) {
    WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
    WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
    WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

    when(webClient.post()).thenReturn(uriSpec);
    when(uriSpec.uri(anyString())).thenReturn(bodySpec);
    when(bodySpec.header(any(), any())).thenReturn(bodySpec);
    when(bodySpec.bodyValue(any())).thenReturn(headersSpec);
    when(headersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.bodyToMono(JsonNode.class)).thenReturn(responseMono);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void stubGet(Mono<JsonNode> responseMono) {
    WebClient.RequestHeadersUriSpec uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
    WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

    when(webClient.get()).thenReturn(uriSpec);
    when(uriSpec.uri(anyString())).thenReturn(headersSpec);
    when(headersSpec.header(any(), any())).thenReturn(headersSpec);
    when(headersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.bodyToMono(JsonNode.class)).thenReturn(responseMono);
  }

  private JsonNode json(String json) throws Exception {
    return objectMapper.readTree(json);
  }

  // =====================================================================
  // getOAuthUrl
  // =====================================================================

  @Nested
  @DisplayName("getOAuthUrl")
  class GetOAuthUrlTests {

    @Test
    @DisplayName("should build the GitHub authorize URL from the configured client id and redirect")
    void shouldBuildAuthorizeUrl() {
      // When
      String url = githubApiClient.getOAuthUrl();

      // Then
      assertThat(url)
          .startsWith("https://github.com/login/oauth/authorize?")
          .contains("client_id=test-client-id")
          .contains("redirect_uri=https://app.test/oauth/callback");
    }

    @Test
    @DisplayName("should point the link flow at its own callback, not the sign-in one")
    void shouldUseSeparateCallbackForLinking() {
      // When
      String url = githubApiClient.getLinkOAuthUrl();

      // Then: the two flows must not share a callback — one GitHub code, two consumers, and the
      // login handler always got there first, so linking could never complete (B23a).
      assertThat(url)
          .contains("redirect_uri=https://app.test/settings/github/callback")
          .doesNotContain("redirect_uri=https://app.test/oauth/callback");
    }
  }

  // =====================================================================
  // exchangeCodeForToken
  // =====================================================================

  @Nested
  @DisplayName("exchangeCodeForToken")
  class ExchangeCodeForTokenTests {

    @Test
    @DisplayName("should return the access token when GitHub responds with one")
    void shouldReturnAccessToken_whenPresent() throws Exception {
      // Given
      stubExchangeCodeForToken(Mono.just(json("{\"access_token\":\"" + ACCESS_TOKEN + "\"}")));

      // When
      String token = githubApiClient.exchangeCodeForToken("some-code");

      // Then
      assertThat(token).isEqualTo(ACCESS_TOKEN);
    }

    @Test
    @DisplayName("should reject when GitHub's response has no access_token field")
    void shouldThrow_whenAccessTokenMissing() throws Exception {
      // Given
      stubExchangeCodeForToken(Mono.just(json("{\"error\":\"bad_verification_code\"}")));

      // When / Then
      assertThatThrownBy(() -> githubApiClient.exchangeCodeForToken("some-code"))
          .isInstanceOf(ExternalApiException.class)
          .hasMessageContaining("Failed to get GitHub access token");
    }

    @Test
    @DisplayName("should reject when GitHub's response is null")
    void shouldThrow_whenResponseIsNull() {
      // Given
      stubExchangeCodeForToken(Mono.empty());

      // When / Then
      assertThatThrownBy(() -> githubApiClient.exchangeCodeForToken("some-code"))
          .isInstanceOf(ExternalApiException.class)
          .hasMessageContaining("Failed to get GitHub access token");
    }
  }

  // =====================================================================
  // getAuthenticatedUser / getUserEmails
  // =====================================================================

  @Nested
  @DisplayName("getAuthenticatedUser")
  class GetAuthenticatedUserTests {

    @Test
    @DisplayName("should return whatever GitHub's /user endpoint responds with")
    void shouldReturnGithubUser() throws Exception {
      // Given
      JsonNode user = json("{\"login\":\"" + USERNAME + "\"}");
      stubGet(Mono.just(user));

      // When
      JsonNode result = githubApiClient.getAuthenticatedUser(ACCESS_TOKEN);

      // Then
      assertThat(result.get("login").asText()).isEqualTo(USERNAME);
    }
  }

  @Nested
  @DisplayName("getUserEmails")
  class GetUserEmailsTests {

    @Test
    @DisplayName("should return whatever GitHub's /user/emails endpoint responds with")
    void shouldReturnGithubUserEmails() throws Exception {
      // Given
      JsonNode emails = json("[{\"email\":\"octo@example.com\",\"primary\":true}]");
      stubGet(Mono.just(emails));

      // When
      JsonNode result = githubApiClient.getUserEmails(ACCESS_TOKEN);

      // Then
      assertThat(result.get(0).get("email").asText()).isEqualTo("octo@example.com");
    }
  }

  // =====================================================================
  // fetchPinnedRepos
  // =====================================================================

  @Nested
  @DisplayName("fetchPinnedRepos")
  class FetchPinnedReposTests {

    @Test
    @DisplayName("should return the pinned repo nodes when the GraphQL response has data")
    void shouldReturnPinnedRepoNodes_whenDataPresent() throws Exception {
      // Given
      JsonNode response =
          json("{\"data\":{\"user\":{\"pinnedItems\":{\"nodes\":[{\"name\":\"hello-world\"}]}}}}");
      stubGraphQlPost(Mono.just(response));

      // When
      JsonNode result = githubApiClient.fetchPinnedRepos(USERNAME, ACCESS_TOKEN);

      // Then
      assertThat(result.get(0).get("name").asText()).isEqualTo("hello-world");
    }

    @Test
    @DisplayName("should return an empty array when the GraphQL response has no data")
    void shouldReturnEmptyArray_whenDataMissing() throws Exception {
      // Given
      stubGraphQlPost(Mono.just(json("{\"errors\":[{\"message\":\"not found\"}]}")));

      // When
      JsonNode result = githubApiClient.fetchPinnedRepos(USERNAME, ACCESS_TOKEN);

      // Then
      assertThat(result.isArray()).isTrue();
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should return an empty array when the GraphQL response is null")
    void shouldReturnEmptyArray_whenResponseIsNull() {
      // Given
      stubGraphQlPost(Mono.empty());

      // When
      JsonNode result = githubApiClient.fetchPinnedRepos(USERNAME, ACCESS_TOKEN);

      // Then
      assertThat(result.isArray()).isTrue();
      assertThat(result).isEmpty();
    }
  }

  // =====================================================================
  // fetchContributionGraph
  // =====================================================================

  @Nested
  @DisplayName("fetchContributionGraph")
  class FetchContributionGraphTests {

    @Test
    @DisplayName("should return the contribution calendar when the GraphQL response has data")
    void shouldReturnContributionCalendar_whenDataPresent() throws Exception {
      // Given
      JsonNode response =
          json(
              "{\"data\":{\"user\":{\"contributionsCollection\":{\"contributionCalendar\":"
                  + "{\"totalContributions\":42}}}}}");
      stubGraphQlPost(Mono.just(response));

      // When
      JsonNode result = githubApiClient.fetchContributionGraph(USERNAME, ACCESS_TOKEN);

      // Then
      assertThat(result.get("totalContributions").asInt()).isEqualTo(42);
    }

    @Test
    @DisplayName("should return an empty object when the GraphQL response has no data")
    void shouldReturnEmptyObject_whenDataMissing() throws Exception {
      // Given
      stubGraphQlPost(Mono.just(json("{\"errors\":[{\"message\":\"not found\"}]}")));

      // When
      JsonNode result = githubApiClient.fetchContributionGraph(USERNAME, ACCESS_TOKEN);

      // Then
      assertThat(result.isObject()).isTrue();
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should return an empty object when the GraphQL response is null")
    void shouldReturnEmptyObject_whenResponseIsNull() {
      // Given
      stubGraphQlPost(Mono.empty());

      // When
      JsonNode result = githubApiClient.fetchContributionGraph(USERNAME, ACCESS_TOKEN);

      // Then
      assertThat(result.isObject()).isTrue();
      assertThat(result).isEmpty();
    }
  }
}
