package com.socialapp.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.blocks.entity.UserBlockId;
import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.chat.client.StreamChatClient;
import com.socialapp.chat.config.StreamChatProperties;
import com.socialapp.chat.dto.ChatTokenResponse;
import com.socialapp.chat.dto.GroupChatResponse;
import com.socialapp.common.exception.ExternalApiException;
import com.socialapp.common.exception.MissingConfigurationException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
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
  @Captor private ArgumentCaptor<Collection<Integer>> membersSent;

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

  // =====================================================================
  // ensureChatParticipants  (C3 — chat opened beyond the friend circle)
  // =====================================================================

  @Nested
  @DisplayName("ensureChatParticipants")
  class EnsureChatParticipantsTests {

    @Test
    @DisplayName("should upsert both people so a channel with a non-friend can be created")
    void shouldUpsertBothUsers() {
      // Given
      UserEntity other = friend(7);
      given(blockQueryService.isBlockedEitherWay(42, 7)).willReturn(false);
      given(userRepository.findAllById(List.of(42, 7))).willReturn(List.of(user, other));

      // When
      service.ensureChatParticipants(42, 7);

      // Then: Stream refuses a channel containing a user it has never seen, which is exactly why
      // chat only ever worked between friends
      then(streamChatClient).should().upsertUsers(upsertedUsers.capture());
      assertThat(upsertedUsers.getValue()).extracting(UserEntity::getId).containsExactly(42, 7);
    }

    @Test
    @DisplayName("should refuse a pair separated by a block, in either direction")
    void shouldRefuseBlockedPair() {
      // Given
      given(blockQueryService.isBlockedEitherWay(42, 7)).willReturn(true);

      // When / Then: the message deliberately does not confirm that a block is the reason
      assertThatThrownBy(() -> service.ensureChatParticipants(42, 7))
          .isInstanceOf(ValidationException.class)
          .hasMessageNotContaining("block");
      then(streamChatClient).should(never()).upsertUsers(any());
    }

    @Test
    @DisplayName("should refuse a conversation with yourself")
    void shouldRefuseSelfConversation() {
      // When / Then
      assertThatThrownBy(() -> service.ensureChatParticipants(42, 42))
          .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("should 404 when the other user does not exist")
    void shouldThrowWhenOtherUserMissing() {
      // Given
      given(blockQueryService.isBlockedEitherWay(42, 7)).willReturn(false);
      given(userRepository.findAllById(List.of(42, 7))).willReturn(List.of(user));

      // When / Then
      assertThatThrownBy(() -> service.ensureChatParticipants(42, 7))
          .isInstanceOf(NotFoundException.class);
    }
  }

  // =====================================================================
  // applyBlock / liftBlock  (C3 — BE-1: the block finally reaches Stream)
  // =====================================================================

  @Nested
  @DisplayName("applyBlock / liftBlock")
  class BlockSyncTests {

    @Test
    @DisplayName("should send the block to Stream in BOTH directions")
    void shouldBlockBothDirections() {
      // When
      service.applyBlock(1, 2);

      // Then: Stream's block belongs to the user who placed it, while this product's block is
      // mutual — sending only the blocker's direction leaves the blocked user able to open the
      // channel, which is the wrong half to enforce.
      then(streamChatClient).should().blockUser(1, 2);
      then(streamChatClient).should().blockUser(2, 1);
    }

    @Test
    @DisplayName("should lift the block on Stream in both directions too")
    void shouldUnblockBothDirections() {
      // When
      service.liftBlock(1, 2);

      // Then
      then(streamChatClient).should().unblockUser(1, 2);
      then(streamChatClient).should().unblockUser(2, 1);
    }

    @Test
    @DisplayName("should do nothing, and not fail, when Stream is not configured")
    void shouldSkipWhenUnconfigured() {
      // Given: exactly the local dev situation — no stream.chat.api-key/api-secret
      properties.setApiKey(null);
      properties.setApiSecret(null);

      // When / Then
      assertThatCode(() -> service.applyBlock(1, 2)).doesNotThrowAnyException();
      then(streamChatClient).should(never()).blockUser(any(), any());
    }

    @Test
    @DisplayName("should swallow a Stream failure — the local block is the source of truth")
    void shouldSwallowStreamFailure() {
      // Given
      willThrow(new ExternalApiException("Stream is down")).given(streamChatClient).blockUser(1, 2);

      // When / Then: if Stream is unreachable the pair is still blocked everywhere this backend
      // controls, so the block call itself must not fail
      assertThatCode(() -> service.applyBlock(1, 2)).doesNotThrowAnyException();
    }
  }

  // =====================================================================
  // createGroupChat  (the one channel this backend creates itself)
  // =====================================================================

  /**
   * Component tests for group creation, per ISTQB CTFL v4.0.1: Section 4.2.1 equivalence
   * partitioning over the member-count partitions (too few after de-duplication / valid / an id
   * with no user behind it) and Section 4.3.2 branch testing over the three block outcomes — none,
   * one involving the caller, one between two other members — which produce three different
   * messages on purpose.
   */
  @Nested
  @DisplayName("createGroupChat")
  class CreateGroupChatTests {

    private static final Integer CALLER = 42;

    private void givenUsersExist(Integer... ids) {
      List<UserEntity> found = new ArrayList<>();
      for (Integer id : ids) {
        found.add(id.equals(CALLER) ? user : friend(id));
      }
      given(userRepository.findAllById(any())).willReturn(found);
    }

    @Test
    @DisplayName("should create the channel with the caller as owner and first member")
    void shouldCreateChannel() {
      // Given
      givenUsersExist(CALLER, 7, 8);
      given(blockQueryService.blocksAmong(any())).willReturn(List.of());

      // When
      GroupChatResponse response =
          service.createGroupChat(CALLER, "Nhom DATN", null, List.of(7, 8));

      // Then: the caller is never asked for in the request — they are taken from the authenticated
      // principal, so a request cannot build a group it is not part of nor give ownership away
      assertThat(response.getMemberIds()).containsExactly("42", "7", "8");
      assertThat(response.getCreatedBy()).isEqualTo("42");
      assertThat(response.getChannelType()).isEqualTo("messaging");
      assertThat(response.getCid()).isEqualTo("messaging:" + response.getChannelId());
      assertThat(response.getName()).isEqualTo("Nhom DATN");
    }

    @Test
    @DisplayName("should upsert every member BEFORE creating the channel")
    void shouldUpsertBeforeCreate() {
      // Given
      givenUsersExist(CALLER, 7, 8);
      given(blockQueryService.blocksAmong(any())).willReturn(List.of());

      // When
      service.createGroupChat(CALLER, "Nhom DATN", null, List.of(7, 8));

      // Then: reversed, Stream answers that the users involved in the channel create operation do
      // not exist, and the group is never created
      InOrder inOrder = inOrder(streamChatClient);
      inOrder.verify(streamChatClient).upsertUsers(any());
      inOrder.verify(streamChatClient).createGroupChannel(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("should drop duplicates and the caller's own id from the member list")
    void shouldDeduplicateMembers() {
      // Given: a member picker that let the same person be chosen twice, plus a frontend that
      // included the caller
      givenUsersExist(CALLER, 7, 8);
      given(blockQueryService.blocksAmong(any())).willReturn(List.of());

      // When
      GroupChatResponse response =
          service.createGroupChat(CALLER, "Nhom DATN", null, List.of(7, 7, 8, CALLER));

      // Then
      assertThat(response.getMemberIds()).containsExactly("42", "7", "8");
      then(streamChatClient)
          .should()
          .createGroupChannel(any(), any(), any(), eq(CALLER), membersSent.capture());
      assertThat(membersSent.getValue()).containsExactly(42, 7, 8);
    }

    @Test
    @DisplayName("should refuse a group that is only the caller and one other after de-duplication")
    void shouldRefuseTooSmallGroup() {
      // When / Then: two people with a name on it is a direct message, and creating it here would
      // give a pair who already have a channel a second one — bean validation cannot catch this,
      // because a list of the same id twice passes @Size(min = 2)
      assertThatThrownBy(() -> service.createGroupChat(CALLER, "Nhom DATN", null, List.of(7, 7)))
          .isInstanceOf(ValidationException.class);
      then(streamChatClient).should(never()).createGroupChannel(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("should 404, naming the id, when a member does not exist")
    void shouldThrowWhenMemberMissing() {
      // Given: 8 has no row in Postgres
      givenUsersExist(CALLER, 7);

      // When / Then
      assertThatThrownBy(() -> service.createGroupChat(CALLER, "Nhom DATN", null, List.of(7, 8)))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("8");
      then(streamChatClient).should(never()).upsertUsers(any());
    }

    @Test
    @DisplayName("should refuse, naming them, members the caller is blocked from")
    void shouldRefuseBlockInvolvingCaller() {
      // Given
      givenUsersExist(CALLER, 7, 8);
      given(blockQueryService.blocksAmong(any())).willReturn(List.of(new UserBlockId(7, CALLER)));

      // When / Then: naming 7 tells the caller nothing they could not already learn by calling
      // ensureChatParticipants for that one person, and it lets the member picker mark them — but
      // the message still does not say the word, same rule as the friend-request rejection
      assertThatThrownBy(() -> service.createGroupChat(CALLER, "Nhom DATN", null, List.of(7, 8)))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("7")
          .hasMessageNotContaining("block");
      then(streamChatClient).should(never()).createGroupChannel(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("should refuse WITHOUT naming anyone when two other members blocked each other")
    void shouldRefuseBlockBetweenOtherMembersWithoutNamingThem() {
      // Given: the caller is on good terms with both; 7 and 8 are not with each other
      givenUsersExist(CALLER, 7, 8);
      given(blockQueryService.blocksAmong(any())).willReturn(List.of(new UserBlockId(7, 8)));

      // When / Then: naming the two would hand anyone a way to probe for blocks between people
      // they know, by assembling groups and reading the error back
      assertThatThrownBy(() -> service.createGroupChat(CALLER, "Nhom DATN", null, List.of(7, 8)))
          .isInstanceOf(ValidationException.class)
          .hasMessageNotContaining("7")
          .hasMessageNotContaining("8")
          .hasMessageNotContaining("block");
      then(streamChatClient).should(never()).createGroupChannel(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("should give two groups with identical members two different channels")
    void shouldNotReuseAChannelIdAcrossGroups() {
      // Given
      givenUsersExist(CALLER, 7, 8);
      given(blockQueryService.blocksAmong(any())).willReturn(List.of());

      // When
      String first = service.createGroupChat(CALLER, "Do an", null, List.of(7, 8)).getChannelId();
      String second =
          service.createGroupChat(CALLER, "An trua", null, List.of(7, 8)).getChannelId();

      // Then: Stream's create verb is get-or-create, so an id derived from the membership would
      // silently hand the second group the first one's channel instead of failing
      assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("should reject when Stream is not configured, before touching the database")
    void shouldRejectWhenUnconfigured() {
      // Given: the local dev situation
      properties.setApiKey(null);
      properties.setApiSecret(null);

      // When / Then
      assertThatThrownBy(() -> service.createGroupChat(CALLER, "Nhom DATN", null, List.of(7, 8)))
          .isInstanceOf(MissingConfigurationException.class);
      then(userRepository).should(never()).findAllById(any());
    }
  }
}
