package com.socialapp.security.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.socialapp.common.exception.ExternalApiException;

@Component
public class GoogleApiClient {

  private final WebClient webClient;

  @Value("${google.oauth.client-id:}")
  private String clientId;

  @Value("${google.oauth.client-secret:}")
  private String clientSecret;

  @Value("${google.oauth.redirect-uri:}")
  private String redirectUri;

  public GoogleApiClient(WebClient.Builder webClientBuilder) {
    this.webClient = webClientBuilder.build();
  }

  public String getOAuthUrl() {
    return String.format(
        "https://accounts.google.com/o/oauth2/v2/auth?client_id=%s&redirect_uri=%s&response_type=code&scope=email%%20profile",
        clientId, redirectUri);
  }

  public String exchangeCodeForToken(String code) {
    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
    formData.add("client_id", clientId);
    formData.add("client_secret", clientSecret);
    formData.add("code", code);
    formData.add("redirect_uri", redirectUri);
    formData.add("grant_type", "authorization_code");

    JsonNode response =
        webClient
            .post()
            .uri("https://oauth2.googleapis.com/token")
            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue(formData)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block();

    if (response != null && response.has("access_token")) {
      return response.get("access_token").asText();
    }
    throw new ExternalApiException("Failed to get Google access token");
  }

  public JsonNode getUserInfo(String accessToken) {
    return webClient
        .get()
        .uri("https://www.googleapis.com/oauth2/v3/userinfo")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        .retrieve()
        .bodyToMono(JsonNode.class)
        .block();
  }
}
