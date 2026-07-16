package com.socialapp.github.service;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.socialapp.github.dto.GithubOAuthUrlResponse;
import com.socialapp.github.dto.GithubStatsResponse;
import com.socialapp.github.entity.GithubStatsEntity;
import com.socialapp.github.repository.GithubStatsRepository;
import com.socialapp.security.entity.UserEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class GithubService {

  private final GithubApiClient githubApiClient;
  private final GithubStatsRepository githubStatsRepository;

  public GithubOAuthUrlResponse getOAuthUrl() {
    return GithubOAuthUrlResponse.builder().oauthUrl(githubApiClient.getOAuthUrl()).build();
  }

  @Transactional
  public void linkAccountWithCode(UserEntity user, String code) {
    String accessToken = githubApiClient.exchangeCodeForToken(code);
    JsonNode githubUser = githubApiClient.getAuthenticatedUser(accessToken);

    if (!githubUser.has("login")) {
      throw new RuntimeException("Invalid GitHub user response");
    }

    String githubUsername = githubUser.get("login").asText();
    Integer publicRepos =
        githubUser.has("public_repos") ? githubUser.get("public_repos").asInt() : 0;
    Integer followers = githubUser.has("followers") ? githubUser.get("followers").asInt() : 0;

    Optional<GithubStatsEntity> existingOpt = githubStatsRepository.findByUserId(user.getId());
    GithubStatsEntity entity =
        existingOpt.orElseGet(() -> GithubStatsEntity.builder().user(user).build());

    entity.setGithubUsername(githubUsername);
    entity.setAccessToken(accessToken);
    entity.setPublicReposCount(publicRepos);
    entity.setFollowersCount(followers);

    // Initial sync will fetch pinned repos and graph
    syncGithubData(entity);
  }

  @Transactional
  public void syncGithubData(GithubStatsEntity entity) {
    if (entity.getAccessToken() == null) {
      log.warn("User {} has no access token, skipping sync", entity.getUser().getId());
      return;
    }

    try {
      JsonNode githubUser = githubApiClient.getAuthenticatedUser(entity.getAccessToken());
      if (githubUser.has("public_repos")) {
        entity.setPublicReposCount(githubUser.get("public_repos").asInt());
      }
      if (githubUser.has("followers")) {
        entity.setFollowersCount(githubUser.get("followers").asInt());
      }

      JsonNode pinnedRepos =
          githubApiClient.fetchPinnedRepos(entity.getGithubUsername(), entity.getAccessToken());
      entity.setPinnedReposJson(pinnedRepos);

      JsonNode contributionGraph =
          githubApiClient.fetchContributionGraph(
              entity.getGithubUsername(), entity.getAccessToken());
      entity.setContributionGraphJson(contributionGraph);

      entity.setLastSyncedAt(OffsetDateTime.now());
      githubStatsRepository.save(entity);
      log.info("Successfully synced GitHub stats for user {}", entity.getUser().getId());
    } catch (Exception e) {
      log.error("Error syncing GitHub stats for user {}", entity.getUser().getId(), e);
    }
  }

  @Transactional
  public void unlinkAccount(UserEntity user) {
    githubStatsRepository.findByUserId(user.getId()).ifPresent(githubStatsRepository::delete);
  }

  @Transactional(readOnly = true)
  public GithubStatsResponse getGithubStats(Integer userId) {
    return githubStatsRepository
        .findByUserId(userId)
        .map(
            entity ->
                GithubStatsResponse.builder()
                    .githubUsername(entity.getGithubUsername())
                    .publicReposCount(entity.getPublicReposCount())
                    .followersCount(entity.getFollowersCount())
                    .pinnedRepos(entity.getPinnedReposJson())
                    .contributionGraph(entity.getContributionGraphJson())
                    .lastSyncedAt(entity.getLastSyncedAt())
                    .build())
        .orElse(null); // Or return 404 in controller
  }

  @Transactional
  public void syncNow(Integer userId) {
    GithubStatsEntity entity =
        githubStatsRepository
            .findByUserId(userId)
            .orElseThrow(() -> new RuntimeException("GitHub account not linked"));

    // Basic rate limiting for manual sync: e.g. 1 hour
    if (entity.getLastSyncedAt() != null
        && entity.getLastSyncedAt().plusHours(1).isAfter(OffsetDateTime.now())) {
      throw new RuntimeException("Please wait at least 1 hour before syncing again");
    }

    syncGithubData(entity);
  }
}
