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

  @Value("${github.oauth.redirect-uri:}")
  private String redirectUri;

  public GithubApiClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
    this.webClient = webClientBuilder.build();
    this.objectMapper = objectMapper;
  }

  public String getOAuthUrl() {
    return String.format(
        "https://github.com/login/oauth/authorize?client_id=%s&redirect_uri=%s&scope=read:user%%20user:email",
        clientId, redirectUri);
  }

  public String exchangeCodeForToken(String code) {
    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
    formData.add("client_id", clientId);
    formData.add("client_secret", clientSecret);
    formData.add("code", code);
    formData.add("redirect_uri", redirectUri);

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
    throw new RuntimeException("Failed to get GitHub access token");
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
