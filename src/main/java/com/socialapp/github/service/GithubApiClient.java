package com.socialapp.github.service;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.common.exception.ExternalApiException;
import com.socialapp.common.utils.RedirectUri;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class GithubApiClient {

  private final WebClient webClient;
  private final ObjectMapper objectMapper;

  @Value("${github.oauth.client-id:}")
  private String clientId;

  @Value("${github.oauth.client-secret:}")
  private String clientSecret;

  /**
   * Where GitHub sends the browser after a <b>sign-in</b> with GitHub.
   *
   * <p>Separate from {@link #linkRedirectUri} because the two flows do different things with the
   * code and only one of them can have it. GitHub authorisation codes are single-use: when both
   * flows pointed at the same callback route, an already-signed-in user who clicked "link my
   * GitHub" came back to the login handler, which spent the code creating a session — and the link
   * request that followed found the code already consumed. Linking could not succeed at all
   * ({@code B23a}).
   */
  @Value("${github.oauth.redirect-uri:}")
  private String redirectUri;

  /** Where GitHub sends the browser after <b>linking</b> GitHub to an existing account. */
  @Value("${github.oauth.link-redirect-uri:}")
  private String linkRedirectUri;

  public GithubApiClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
    this.webClient = webClientBuilder.build();
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  void normalizeRedirectUris() {
    this.redirectUri = RedirectUri.normalize(this.redirectUri);
    this.linkRedirectUri = RedirectUri.normalize(this.linkRedirectUri);
  }

  /** Authorisation URL for signing in with GitHub. */
  public String getOAuthUrl() {
    return authorizeUrl(redirectUri);
  }

  /** Authorisation URL for linking GitHub to the account already signed in. */
  public String getLinkOAuthUrl() {
    return authorizeUrl(linkRedirectUri);
  }

  private String authorizeUrl(String callback) {
    return String.format(
        "https://github.com/login/oauth/authorize?client_id=%s&redirect_uri=%s&scope=read:user%%20user:email",
        clientId, callback);
  }

  /** Redeems a code issued for the sign-in flow. */
  public String exchangeCodeForToken(String code) {
    return exchangeCode(code, redirectUri);
  }

  /** Redeems a code issued for the account-linking flow. */
  public String exchangeCodeForLinkToken(String code) {
    return exchangeCode(code, linkRedirectUri);
  }

  /**
   * {@code redirect_uri} has to be passed here too, and has to be the <em>same</em> value that was
   * used to obtain the code — GitHub validates the pair and rejects the exchange otherwise. That
   * coupling is why the two flows need two methods rather than one method and a config switch.
   */
  private String exchangeCode(String code, String callback) {
    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
    formData.add("client_id", clientId);
    formData.add("client_secret", clientSecret);
    formData.add("code", code);
    formData.add("redirect_uri", callback);

    JsonNode response =
        webClient
            .post()
            .uri("https://github.com/login/oauth/access_token")
            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue(formData)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block();

    if (response != null && response.has("access_token")) {
      return response.get("access_token").asText();
    }
    throw new ExternalApiException("Failed to get GitHub access token");
  }

  public JsonNode getAuthenticatedUser(String accessToken) {
    return webClient
        .get()
        .uri("https://api.github.com/user")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        .header("X-GitHub-Api-Version", "2022-11-28")
        .retrieve()
        .bodyToMono(JsonNode.class)
        .block();
  }

  public JsonNode getUserEmails(String accessToken) {
    return webClient
        .get()
        .uri("https://api.github.com/user/emails")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        .header("X-GitHub-Api-Version", "2022-11-28")
        .retrieve()
        .bodyToMono(JsonNode.class)
        .block();
  }

  public JsonNode fetchPinnedRepos(String username, String accessToken) {
    String query =
        "query { user(login: \""
            + username
            + "\") { pinnedItems(first: 6, types: REPOSITORY) { nodes { ... on Repository { name description url stargazerCount forkCount primaryLanguage { name color } } } } } }";

    JsonNode response =
        webClient
            .post()
            .uri("https://api.github.com/graphql")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .bodyValue(Map.of("query", query))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block();

    if (response != null && response.has("data")) {
      return response.at("/data/user/pinnedItems/nodes");
    }
    return objectMapper.createArrayNode();
  }

  public JsonNode fetchContributionGraph(String username, String accessToken) {
    String query =
        "query { user(login: \""
            + username
            + "\") { contributionsCollection { contributionCalendar { totalContributions weeks { contributionDays { date contributionCount color } } } } } }";

    JsonNode response =
        webClient
            .post()
            .uri("https://api.github.com/graphql")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .bodyValue(Map.of("query", query))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block();

    if (response != null && response.has("data")) {
      return response.at("/data/user/contributionsCollection/contributionCalendar");
    }
    return objectMapper.createObjectNode();
  }
}
