package com.socialapp.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.chat.client.StreamChatClient;
import com.socialapp.chat.config.StreamChatProperties;
import com.socialapp.chat.dto.ChatTokenResponse;
import com.socialapp.common.exception.ExternalApiException;
import com.socialapp.common.exception.MissingConfigurationException;
import com.socialapp.friendships.service.FriendshipService;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link StreamChatService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing; Section 4.2.1 equivalence partitioning over the configured/unconfigured
 * partitions; Section 4.3.2 branch testing over the best-effort profile-sync try/catch).
 */
@ExtendWith(MockitoExtension.class)
class StreamChatServiceTest {

  private static final String API_KEY = "stream-public-key";
  private static final String API_SECRET =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @Mock private StreamChatClient streamChatClient;
  @Mock private FriendshipService friendshipService;
  @Mock private UserRepository userRepository;
  @Mock private BlockQueryService blockQueryService;

  @Captor private ArgumentCaptor<Collection<UserEntity>> upsertedUsers;

  private StreamChatProperties properties;
  private StreamChatService service;
  private UserEntity user;

  @BeforeEach
  void setUp() {
    properties = new StreamChatProperties();
    properties.setApiKey(API_KEY);
    properties.setApiSecret(API_SECRET);
    properties.setTokenTtl(Duration.ofHours(24));

    service =
        new StreamChatService(
            properties,
            new StreamTokenSigner(properties),
            streamChatClient,
            friendshipService,
            userRepository,
            blockQueryService);

    user = new UserEntity();
    user.setId(42);
    user.setFullName("Ada Lovelace");
  }

  private static UserEntity friend(int id) {
    UserEntity friend = new UserEntity();
    friend.setId(id);
    friend.setFullName("Friend " + id);
    return friend;
  }

  @Nested
  @DisplayName("issueToken")
  class IssueTokenTests {

    @Test
    @DisplayName("should return the public api key alongside the token")
    void shouldReturnApiKey_soTheFrontendHasASingleSourceOfTruth() {
      // When
      ChatTokenResponse response = service.issueToken(user);

      // Then
      assertThat(response.getApiKey()).isEqualTo(API_KEY);
      assertThat(response.getUserId()).isEqualTo("42");
      assertThat(response.getStreamToken()).isNotBlank();
    }

    @Test
    @DisplayName("should return an expiry roughly one token-ttl into the future")
    void shouldReturnExpiresAt_derivedFromConfiguredTtl() {
      // Given
      Instant before = Instant.now();

      // When
      ChatTokenResponse response = service.issueToken(user);

      // Then
      assertThat(response.getExpiresAt())
          .isBetween(before.plus(Duration.ofHours(24)), Instant.now().plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("should push the user profile to Stream so the UI shows names, not numeric ids")
    void shouldUpsertUser() {
      // Given
      given(friendshipService.getFriendIds(42)).willReturn(List.of());

      // When
      service.issueToken(user);

      // Then
      then(streamChatClient).should().upsertUsers(upsertedUsers.capture());
      assertThat(upsertedUsers.getValue()).containsExactly(user);
    }

    @Test
    @DisplayName("should push the caller's friends in the same call so channels can be created")
    void shouldUpsertFriendsToo_becauseStreamRejectsChannelsWithUnknownMembers() {
      // Given
      given(friendshipService.getFriendIds(42)).willReturn(List.of(7, 8));
      given(userRepository.findAllById(List.of(7, 8))).willReturn(List.of(friend(7), friend(8)));

      // When
      service.issueToken(user);

      // Then: one call, three users - not one call per user
      then(streamChatClient).should().upsertUsers(upsertedUsers.capture());
      assertThat(upsertedUsers.getValue())
          .extracting(UserEntity::getId)
          .containsExactlyInAnyOrder(42, 7, 8);
    }

    @Test
    @DisplayName("should skip friend ids that no longer exist in Postgres")
    void shouldSkipDanglingFriendIds_soNoNullsReachTheStreamPayload() {
      // Given: 9 is still an edge in Neo4j but its account is gone
      given(friendshipService.getFriendIds(42)).willReturn(List.of(7, 9));
      given(userRepository.findAllById(List.of(7, 9))).willReturn(List.of(friend(7)));

      // When
      service.issueToken(user);

      // Then
      then(streamChatClient).should().upsertUsers(upsertedUsers.capture());
      assertThat(upsertedUsers.getValue()).extracting(UserEntity::getId).containsExactly(42, 7);
    }

    @Test
    @DisplayName("should not query Postgres at all when the user has no friends")
    void shouldStillUpsertSelf_whenFriendListIsEmpty() {
      // Given
      given(friendshipService.getFriendIds(42)).willReturn(List.of());

      // When
      service.issueToken(user);

      // Then
      then(userRepository).should(never()).findAllById(any());
      then(streamChatClient).should().upsertUsers(upsertedUsers.capture());
      assertThat(upsertedUsers.getValue()).containsExactly(user);
    }

    @Test
    @DisplayName("should still issue a token when the friend lookup fails")
    void shouldSwallowFriendLookupFailure_soNeo4jCannotBlockChat() {
      // Given
      willThrow(new IllegalStateException("Neo4j unreachable"))
          .given(friendshipService)
          .getFriendIds(42);

      // When / Then
      assertThatCode(() -> assertThat(service.issueToken(user).getStreamToken()).isNotBlank())
          .doesNotThrowAnyException();
      then(streamChatClient).should(never()).upsertUsers(any());
    }

    @Test
    @DisplayName("should still issue a token when the profile sync fails")
    void shouldSwallowSyncFailure_becauseADegradedUiBeatsNoChatAtAll() {
      // Given
      given(friendshipService.getFriendIds(42)).willReturn(List.of());
      willThrow(new ExternalApiException("Stream unreachable"))
          .given(streamChatClient)
          .upsertUsers(any());

      // When / Then
      assertThatCode(() -> assertThat(service.issueToken(user).getStreamToken()).isNotBlank())
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should reject when the api-secret is missing")
    void shouldThrowMissingConfigurationException_whenSecretIsMissing() {
      // Given
      properties.setApiSecret("");

      // When / Then
      assertThatThrownBy(() -> service.issueToken(user))
          .isInstanceOf(MissingConfigurationException.class);
      then(streamChatClient).should(never()).upsertUsers(any());
    }

    @Test
    @DisplayName("should reject when the api-key is missing")
    void shouldThrowMissingConfigurationException_whenKeyIsMissing() {
      // Given: a token signed against an app whose public key the frontend never receives is
      // unusable, so this fails loudly at the same 503 rather than returning a null apiKey.
      properties.setApiKey(null);

      // When / Then
      assertThatThrownBy(() -> service.issueToken(user))
          .isInstanceOf(MissingConfigurationException.class);
      then(streamChatClient).should(never()).upsertUsers(any());
    }
  }

  @Nested
  @DisplayName("issueToken — block filtering")
  class BlockFilteringTests {

    @Test
    @DisplayName("should not push a blocked friend's profile to Stream")
    void shouldSkipBlockedFriends() {
      // Given
      given(friendshipService.getFriendIds(42)).willReturn(List.of(7, 8));
      given(blockQueryService.blockedPairIds(42)).willReturn(java.util.Set.of(8));
      given(userRepository.findAllById(List.of(7))).willReturn(List.of(friend(7)));

      // When
      service.issueToken(user);

      // Then — this is a partial defence only: it stops THIS server introducing the two to
      // Stream, it cannot stop a channel Stream would create on its own. See the method's javadoc.
      then(streamChatClient).should().upsertUsers(upsertedUsers.capture());
      assertThat(upsertedUsers.getValue()).extracting(UserEntity::getId).containsExactly(42, 7);
    }
  }
}
