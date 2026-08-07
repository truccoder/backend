package com.socialapp.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.common.exception.ExternalApiException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.github.dto.GithubOAuthUrlResponse;
import com.socialapp.github.dto.GithubStatsResponse;
import com.socialapp.github.entity.GithubStatsEntity;
import com.socialapp.github.repository.GithubStatsRepository;
import com.socialapp.security.entity.UserEntity;

/**
 * Component (unit) tests for {@link GithubService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing; Section 4.3.2 branch testing over the new/existing-link, present/missing
 * profile-field, and never/recently/long-ago-synced partitions; Section 4.5.3 Error Guessing for
 * a GitHub API failure mid-sync). {@link GithubApiClient} is mocked — no real HTTP call to GitHub
 * is made. A real {@link ObjectMapper} builds the {@link JsonNode} fixtures.
 */
@ExtendWith(MockitoExtension.class)
class GithubServiceTest {

  private static final Integer USER_ID = 1;
  private static final String ACCESS_TOKEN = "gho_test-token";
  private static final String USERNAME = "octocat";

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Mock private GithubApiClient githubApiClient;
  @Mock private GithubStatsRepository githubStatsRepository;

  @InjectMocks private GithubService githubService;

  private static UserEntity user(Integer id) {
    UserEntity user = new UserEntity();
    user.setId(id);
    return user;
  }

  private static GithubStatsEntity statsEntity(Integer userId, String accessToken) {
    return GithubStatsEntity.builder()
        .user(user(userId))
        .githubUsername(USERNAME)
        .accessToken(accessToken)
        .build();
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
    @DisplayName("should wrap the api client's authorize URL in the response DTO")
    void shouldDelegateToApiClient() {
      // Given
      // GithubService serves the LINK flow, which has its own callback route (E4/B23a).
      when(githubApiClient.getLinkOAuthUrl())
          .thenReturn("https://github.com/login/oauth/authorize?client_id=x");

      // When
      GithubOAuthUrlResponse response = githubService.getOAuthUrl();

      // Then
      assertThat(response.getOauthUrl())
          .isEqualTo("https://github.com/login/oauth/authorize?client_id=x");
    }
  }

  // =====================================================================
  // linkAccountWithCode
  // =====================================================================

  @Nested
  @DisplayName("linkAccountWithCode")
  class LinkAccountWithCodeTests {

    @Test
    @DisplayName("should exchange the code, fetch the profile, and persist new GitHub stats")
    void shouldLinkNewAccount() throws Exception {
      // Given
      JsonNode githubUser =
          json("{\"login\":\"" + USERNAME + "\",\"public_repos\":5,\"followers\":10}");
      when(githubApiClient.exchangeCodeForLinkToken("auth-code")).thenReturn(ACCESS_TOKEN);
      when(githubApiClient.getAuthenticatedUser(ACCESS_TOKEN)).thenReturn(githubUser);
      when(githubStatsRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
      when(githubApiClient.fetchPinnedRepos(USERNAME, ACCESS_TOKEN))
          .thenReturn(objectMapper.createArrayNode());
      when(githubApiClient.fetchContributionGraph(USERNAME, ACCESS_TOKEN))
          .thenReturn(objectMapper.createObjectNode());

      // When
      githubService.linkAccountWithCode(user(USER_ID), "auth-code");

      // Then
      ArgumentCaptor<GithubStatsEntity> captor = ArgumentCaptor.forClass(GithubStatsEntity.class);
      verify(githubStatsRepository).save(captor.capture());
      GithubStatsEntity saved = captor.getValue();
      assertThat(saved.getGithubUsername()).isEqualTo(USERNAME);
      assertThat(saved.getPublicReposCount()).isEqualTo(5);
      assertThat(saved.getFollowersCount()).isEqualTo(10);
      assertThat(saved.getLastSyncedAt()).isNotNull();
    }
  }

  // =====================================================================
  // linkAccountWithTokenAndProfile
  // =====================================================================

  @Nested
  @DisplayName("linkAccountWithTokenAndProfile")
  class LinkAccountWithTokenAndProfileTests {

    @Test
    @DisplayName("should reject when GitHub's user response has no login field")
    void shouldThrow_whenLoginMissing() throws Exception {
      // Given
      JsonNode githubUser = json("{\"id\":123}");

      // When / Then
      assertThatThrownBy(
              () ->
                  githubService.linkAccountWithTokenAndProfile(
                      user(USER_ID), ACCESS_TOKEN, githubUser))
          .isInstanceOf(ExternalApiException.class)
          .hasMessageContaining("Invalid GitHub user response");
    }

    @Test
    @DisplayName("should update the existing GitHub stats entity when one is already linked")
    void shouldUpdateExistingEntity() throws Exception {
      // Given
      GithubStatsEntity existing = statsEntity(USER_ID, "old-token");
      JsonNode githubUser =
          json("{\"login\":\"" + USERNAME + "\",\"public_repos\":7,\"followers\":20}");
      when(githubStatsRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
      when(githubApiClient.getAuthenticatedUser(ACCESS_TOKEN)).thenReturn(githubUser);
      when(githubApiClient.fetchPinnedRepos(USERNAME, ACCESS_TOKEN))
          .thenReturn(objectMapper.createArrayNode());
      when(githubApiClient.fetchContributionGraph(USERNAME, ACCESS_TOKEN))
          .thenReturn(objectMapper.createObjectNode());

      // When
      githubService.linkAccountWithTokenAndProfile(user(USER_ID), ACCESS_TOKEN, githubUser);

      // Then
      assertThat(existing.getAccessToken()).isEqualTo(ACCESS_TOKEN);
      assertThat(existing.getPublicReposCount()).isEqualTo(7);
      assertThat(existing.getFollowersCount()).isEqualTo(20);
      verify(githubStatsRepository).save(existing);
    }

    @Test
    @DisplayName("should default repo/follower counts to zero when GitHub omits them")
    void shouldDefaultCountsToZero_whenFieldsMissing() throws Exception {
      // Given
      JsonNode githubUser = json("{\"login\":\"" + USERNAME + "\"}");
      when(githubStatsRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
      when(githubApiClient.getAuthenticatedUser(ACCESS_TOKEN)).thenReturn(githubUser);
      when(githubApiClient.fetchPinnedRepos(USERNAME, ACCESS_TOKEN))
          .thenReturn(objectMapper.createArrayNode());
      when(githubApiClient.fetchContributionGraph(USERNAME, ACCESS_TOKEN))
          .thenReturn(objectMapper.createObjectNode());

      // When
      githubService.linkAccountWithTokenAndProfile(user(USER_ID), ACCESS_TOKEN, githubUser);

      // Then
      ArgumentCaptor<GithubStatsEntity> captor = ArgumentCaptor.forClass(GithubStatsEntity.class);
      verify(githubStatsRepository).save(captor.capture());
      assertThat(captor.getValue().getPublicReposCount()).isEqualTo(0);
      assertThat(captor.getValue().getFollowersCount()).isEqualTo(0);
    }
  }

  // =====================================================================
  // syncGithubData
  // =====================================================================

  @Nested
  @DisplayName("syncGithubData")
  class SyncGithubDataTests {

    @Test
    @DisplayName("should skip syncing when the entity has no access token")
    void shouldSkipSync_whenAccessTokenIsNull() {
      // Given
      GithubStatsEntity entity = statsEntity(USER_ID, null);

      // When
      githubService.syncGithubData(entity);

      // Then
      verify(githubStatsRepository, never()).save(any());
      verify(githubApiClient, never()).getAuthenticatedUser(any());
    }

    @Test
    @DisplayName("should refresh repo count, followers, pinned repos, and contribution graph")
    void shouldSyncAllFields_whenAccessTokenPresent() throws Exception {
      // Given
      GithubStatsEntity entity = statsEntity(USER_ID, ACCESS_TOKEN);
      JsonNode githubUser = json("{\"public_repos\":9,\"followers\":3}");
      JsonNode pinned = json("[{\"name\":\"repo\"}]");
      JsonNode graph = json("{\"totalContributions\":100}");
      when(githubApiClient.getAuthenticatedUser(ACCESS_TOKEN)).thenReturn(githubUser);
      when(githubApiClient.fetchPinnedRepos(USERNAME, ACCESS_TOKEN)).thenReturn(pinned);
      when(githubApiClient.fetchContributionGraph(USERNAME, ACCESS_TOKEN)).thenReturn(graph);

      // When
      githubService.syncGithubData(entity);

      // Then
      assertThat(entity.getPublicReposCount()).isEqualTo(9);
      assertThat(entity.getFollowersCount()).isEqualTo(3);
      assertThat(entity.getPinnedReposJson()).isEqualTo(pinned);
      assertThat(entity.getContributionGraphJson()).isEqualTo(graph);
      assertThat(entity.getLastSyncedAt()).isNotNull();
      verify(githubStatsRepository).save(entity);
    }

    @Test
    @DisplayName("should leave repo/follower counts untouched when GitHub omits them on refresh")
    void shouldNotOverwriteCounts_whenFieldsMissingOnRefresh() throws Exception {
      // Given
      GithubStatsEntity entity = statsEntity(USER_ID, ACCESS_TOKEN);
      entity.setPublicReposCount(42);
      entity.setFollowersCount(24);
      when(githubApiClient.getAuthenticatedUser(ACCESS_TOKEN)).thenReturn(json("{}"));
      when(githubApiClient.fetchPinnedRepos(USERNAME, ACCESS_TOKEN))
          .thenReturn(objectMapper.createArrayNode());
      when(githubApiClient.fetchContributionGraph(USERNAME, ACCESS_TOKEN))
          .thenReturn(objectMapper.createObjectNode());

      // When
      githubService.syncGithubData(entity);

      // Then
      assertThat(entity.getPublicReposCount()).isEqualTo(42);
      assertThat(entity.getFollowersCount()).isEqualTo(24);
    }

    @Test
    @DisplayName("should swallow a GitHub API failure instead of propagating it")
    void shouldSwallowFailure_whenGithubApiThrows() {
      // Given
      GithubStatsEntity entity = statsEntity(USER_ID, ACCESS_TOKEN);
      when(githubApiClient.getAuthenticatedUser(ACCESS_TOKEN))
          .thenThrow(new RuntimeException("GitHub API down"));

      // When / Then: must not propagate
      githubService.syncGithubData(entity);

      verify(githubStatsRepository, never()).save(any());
    }
  }

  // =====================================================================
  // unlinkAccount
  // =====================================================================

  @Nested
  @DisplayName("unlinkAccount")
  class UnlinkAccountTests {

    @Test
    @DisplayName("should delete the linked GitHub stats when present")
    void shouldDelete_whenLinked() {
      // Given
      GithubStatsEntity entity = statsEntity(USER_ID, ACCESS_TOKEN);
      when(githubStatsRepository.findByUserId(USER_ID)).thenReturn(Optional.of(entity));

      // When
      githubService.unlinkAccount(user(USER_ID));

      // Then
      verify(githubStatsRepository).delete(entity);
    }

    @Test
    @DisplayName("should do nothing when no GitHub account is linked")
    void shouldDoNothing_whenNotLinked() {
      // Given
      when(githubStatsRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

      // When
      githubService.unlinkAccount(user(USER_ID));

      // Then
      verify(githubStatsRepository, never()).delete(any());
    }
  }

  // =====================================================================
  // getGithubStats
  // =====================================================================

  @Nested
  @DisplayName("getGithubStats")
  class GetGithubStatsTests {

    @Test
    @DisplayName("should map the entity to a response DTO when linked")
    void shouldReturnStats_whenLinked() {
      // Given
      GithubStatsEntity entity = statsEntity(USER_ID, ACCESS_TOKEN);
      entity.setPublicReposCount(5);
      when(githubStatsRepository.findByUserId(USER_ID)).thenReturn(Optional.of(entity));

      // When
      GithubStatsResponse response = githubService.getGithubStats(USER_ID);

      // Then
      assertThat(response.getGithubUsername()).isEqualTo(USERNAME);
      assertThat(response.getPublicReposCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("should throw NotFound when no GitHub account is linked")
    void shouldThrowNotFound_whenNotLinked() {
      // Given
      when(githubStatsRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> githubService.getGithubStats(USER_ID))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("not linked");
    }
  }

  // =====================================================================
  // syncNow
  // =====================================================================

  @Nested
  @DisplayName("syncNow")
  class SyncNowTests {

    @Test
    @DisplayName("should reject when no GitHub account is linked")
    void shouldThrowNotFoundException_whenNotLinked() {
      // Given
      when(githubStatsRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> githubService.syncNow(USER_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should reject when the last sync was less than an hour ago")
    void shouldThrowIllegalStateException_whenSyncedRecently() {
      // Given
      GithubStatsEntity entity = statsEntity(USER_ID, ACCESS_TOKEN);
      entity.setLastSyncedAt(OffsetDateTime.now().minusMinutes(10));
      when(githubStatsRepository.findByUserId(USER_ID)).thenReturn(Optional.of(entity));

      // When / Then
      assertThatThrownBy(() -> githubService.syncNow(USER_ID))
          .isInstanceOf(IllegalStateException.class);
      verify(githubApiClient, never()).getAuthenticatedUser(any());
    }

    @Test
    @DisplayName("should sync when never synced before")
    void shouldSync_whenNeverSyncedBefore() throws Exception {
      // Given
      GithubStatsEntity entity = statsEntity(USER_ID, ACCESS_TOKEN);
      entity.setLastSyncedAt(null);
      when(githubStatsRepository.findByUserId(USER_ID)).thenReturn(Optional.of(entity));
      when(githubApiClient.getAuthenticatedUser(ACCESS_TOKEN)).thenReturn(json("{}"));
      when(githubApiClient.fetchPinnedRepos(USERNAME, ACCESS_TOKEN))
          .thenReturn(objectMapper.createArrayNode());
      when(githubApiClient.fetchContributionGraph(USERNAME, ACCESS_TOKEN))
          .thenReturn(objectMapper.createObjectNode());

      // When
      githubService.syncNow(USER_ID);

      // Then
      verify(githubStatsRepository).save(entity);
    }

    @Test
    @DisplayName("should sync when the last sync was more than an hour ago")
    void shouldSync_whenLastSyncedOverAnHourAgo() throws Exception {
      // Given
      GithubStatsEntity entity = statsEntity(USER_ID, ACCESS_TOKEN);
      entity.setLastSyncedAt(OffsetDateTime.now().minusHours(2));
      when(githubStatsRepository.findByUserId(USER_ID)).thenReturn(Optional.of(entity));
      when(githubApiClient.getAuthenticatedUser(ACCESS_TOKEN)).thenReturn(json("{}"));
      when(githubApiClient.fetchPinnedRepos(USERNAME, ACCESS_TOKEN))
          .thenReturn(objectMapper.createArrayNode());
      when(githubApiClient.fetchContributionGraph(USERNAME, ACCESS_TOKEN))
          .thenReturn(objectMapper.createObjectNode());

      // When
      githubService.syncNow(USER_ID);

      // Then
      verify(githubStatsRepository).save(entity);
    }
  }
}
