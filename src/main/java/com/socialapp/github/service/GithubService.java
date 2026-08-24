package com.socialapp.github.service;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.socialapp.common.exception.ExternalApiException;
import com.socialapp.common.exception.NotFoundException;
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

  /**
   * The authorisation URL for <b>linking</b>, not for signing in.
   *
   * <p>{@code OAuthAuthService.getGithubOAuthUrl} returns the sign-in one. They used to be the same
   * URL with the same callback, which is what made linking impossible — see {@code
   * GithubApiClient#redirectUri}.
   */
  public GithubOAuthUrlResponse getOAuthUrl() {
    return GithubOAuthUrlResponse.builder().oauthUrl(githubApiClient.getLinkOAuthUrl()).build();
  }

  @Transactional
  public void linkAccountWithCode(UserEntity user, String code) {
    String accessToken = githubApiClient.exchangeCodeForLinkToken(code);
    JsonNode githubUser = githubApiClient.getAuthenticatedUser(accessToken);
    linkAccountWithTokenAndProfile(user, accessToken, githubUser);
  }

  @Transactional
  public void linkAccountWithTokenAndProfile(
      UserEntity user, String accessToken, JsonNode githubUser) {
    if (!githubUser.has("login")) {
      throw new ExternalApiException("Invalid GitHub user response");
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

    // Persist the link before syncing, not after. syncGithubData swallows failures on the promise
    // that "the account stays linked" — but for a first-time link the row was still transient at
    // that point and only ever written inside performSync, three GitHub calls later. Any failure
    // there discarded it: the OAuth round trip completed, the endpoint answered 200, and the
    // account was not linked. Saving here makes the comment true.
    githubStatsRepository.save(entity);

    // Initial sync will fetch pinned repos and graph
    syncGithubData(entity);
  }

  /**
   * Best-effort sync, for the moment an account is first linked.
   *
   * <p>Swallowing the failure is correct <em>here</em>: the link itself succeeded, the token is
   * stored, and failing the whole link because GitHub's GraphQL API was briefly unavailable would
   * throw away a completed OAuth round trip. The stats simply arrive on the next sync.
   *
   * <p>It was <b>not</b> correct for {@link #syncNow}, which is a user pressing "Sync" and waiting
   * for an answer. That path now calls {@link #performSync} and lets the failure surface — see
   * {@code B23b}.
   */
  @Transactional
  public void syncGithubData(GithubStatsEntity entity) {
    try {
      performSync(entity);
    } catch (Exception e) {
      log.error(
          "Error syncing GitHub stats for user {}; the account stays linked",
          entity.getUser().getId(),
          e);
    }
  }

  /**
   * Pulls profile, pinned repos and contribution graph from GitHub, and <b>throws</b> if any of it
   * fails.
   *
   * @throws ExternalApiException if the account has no stored token, or GitHub refused. The token
   *     case is an exception rather than a silent return because the only ways to reach it are a
   *     revoked authorisation or a half-written link row, and both need re-linking — telling the
   *     user "synced" and changing nothing leaves them staring at stale numbers with no reason
   *     given.
   */
  @Transactional
  public void performSync(GithubStatsEntity entity) {
    if (entity.getAccessToken() == null) {
      throw new ExternalApiException(
          "This GitHub account has no stored access token; please link it again");
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
      throw new ExternalApiException("Failed to sync GitHub data: " + e.getMessage(), e);
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
        .orElseThrow(() -> new NotFoundException("GitHub account not linked"));
  }

  @Transactional
  public void syncNow(Integer userId) {
    GithubStatsEntity entity =
        githubStatsRepository
            .findByUserId(userId)
            .orElseThrow(() -> new NotFoundException("GitHub account not linked"));

    // Basic rate limiting for manual sync: e.g. 1 hour
    if (entity.getLastSyncedAt() != null
        && entity.getLastSyncedAt().plusHours(1).isAfter(OffsetDateTime.now())) {
      throw new IllegalStateException("Please wait at least 1 hour before syncing again");
    }

    // performSync, not syncGithubData: a manual sync that fails silently is worse than one that
    // errors — the user pressed a button, watched it say nothing, and has no way to tell a
    // successful no-op from a broken token (B23b).
    performSync(entity);
  }
}
