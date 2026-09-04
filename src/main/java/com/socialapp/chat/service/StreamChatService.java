package com.socialapp.chat.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.socialapp.blocks.entity.UserBlockId;
import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.chat.client.StreamChatClient;
import com.socialapp.chat.config.StreamChatProperties;
import com.socialapp.chat.dto.ChatTokenResponse;
import com.socialapp.chat.dto.GroupChatResponse;
import com.socialapp.common.exception.MissingConfigurationException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.friendships.service.FriendshipService;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class StreamChatService {

  /**
   * Below this, a "group" is a direct message with a name on it. Routing one through here would
   * create a second channel between two people who already have one and split their unread count
   * across the two — the frontend creates direct messages against Stream itself.
   */
  private static final int MIN_OTHER_MEMBERS = 2;

  private final StreamChatProperties properties;
  private final StreamTokenSigner tokenSigner;
  private final StreamChatClient streamChatClient;
  private final FriendshipService friendshipService;
  private final UserRepository userRepository;
  private final BlockQueryService blockQueryService;

  public ChatTokenResponse issueToken(UserEntity user) {
    if (!properties.isConfigured()) {
      throw new MissingConfigurationException(
          "Stream Chat is not configured (missing stream.chat.api-key/api-secret); cannot issue a"
              + " chat token");
    }

    Instant issuedAt = Instant.now();
    Instant expiresAt = issuedAt.plus(properties.getTokenTtl());
    String token = tokenSigner.userToken(user.getId(), issuedAt, expiresAt);

    syncProfilesBestEffort(user);

    return ChatTokenResponse.builder()
        .userId(String.valueOf(user.getId()))
        .apiKey(properties.getApiKey())
        .streamToken(token)
        .expiresAt(expiresAt)
        .build();
  }

  /**
   * Introduces the caller and one other person to Stream so the two can open a conversation.
   *
   * <p>This is what "chat is open to everyone" means in practice. The token call syncs the caller
   * and their friends, which is why chat used to work only between friends — Stream refuses to
   * create a channel containing a user it has never seen. The alternative to this endpoint is
   * upserting the entire user table on every token request, which is not an alternative.
   *
   * <p>Unlike the profile sync this is <b>not</b> best-effort: the caller is about to create a
   * channel, and swallowing a failure here produces the exact "user does not exist" error from
   * Stream that this call exists to prevent, except with no explanation on this side.
   *
   * @throws ValidationException if a block stands between the two, in either direction. The message
   *     deliberately does not say "blocked" — same reasoning as the friend-request rejection: the
   *     product does not confirm to a blocked person that they were blocked.
   */
  // No @Transactional: the only database work is one findAllById, which manages its own, and the
  // method then makes an HTTP round trip to Stream. Wrapping the two together held a Postgres
  // connection open for the duration of somebody else's network latency, for no isolation benefit.
  public void ensureChatParticipants(Integer callerId, Integer otherUserId) {
    if (callerId.equals(otherUserId)) {
      throw new ValidationException("You cannot start a conversation with yourself");
    }
    if (!properties.isConfigured()) {
      throw new MissingConfigurationException(
          "Stream Chat is not configured (missing stream.chat.api-key/api-secret); cannot sync chat"
              + " participants");
    }
    if (blockQueryService.isBlockedEitherWay(callerId, otherUserId)) {
      throw new ValidationException("You cannot start a conversation with this user");
    }

    List<UserEntity> both = userRepository.findAllById(List.of(callerId, otherUserId));
    if (both.size() != 2) {
      throw new NotFoundException("User not found with ID: " + otherUserId);
    }

    streamChatClient.upsertUsers(both);
    log.info("Synced chat participants {} and {} to Stream Chat", callerId, otherUserId);
  }

  /**
   * Mirrors a block into Stream, in both directions.
   *
   * <p>Stream's block is one-directional — it belongs to the user who placed it — while this
   * product's block is mutual: neither person may reach the other. Sending only the blocker's
   * direction would leave the blocked user able to open the channel, which is the wrong half to
   * enforce.
   *
   * <p>Best-effort, and the local block is the source of truth: if Stream is down, the pair is
   * still blocked everywhere this backend controls. Logged at error rather than warn because,
   * unlike a failed profile sync, the visible consequence is a safety feature that silently did not
   * take effect — someone has to see it.
   *
   * <p><b>Not verifiable on a dev machine.</b> Without {@code stream.chat.api-key/api-secret} this
   * returns without calling anything, so local testing exercises the guard and not the call. Unit
   * tests cover the branch; the HTTP request itself has only been checked against Stream's
   * published API shape ({@code POST /users/block}, {@code {user_id, blocked_user_id}}).
   */
  public void applyBlock(Integer blockerId, Integer blockedId) {
    forEachDirection(blockerId, blockedId, streamChatClient::blockUser, "block");
  }

  /** Undoes {@link #applyBlock}. Same both-directions reasoning, same best-effort contract. */
  public void liftBlock(Integer blockerId, Integer blockedId) {
    forEachDirection(blockerId, blockedId, streamChatClient::unblockUser, "unblock");
  }

  private void forEachDirection(
      Integer blockerId, Integer blockedId, BiConsumer<Integer, Integer> call, String action) {
    if (!properties.isConfigured()) {
      log.debug(
          "Stream Chat not configured; skipping {} sync for {}/{}", action, blockerId, blockedId);
      return;
    }
    try {
      call.accept(blockerId, blockedId);
      call.accept(blockedId, blockerId);
      log.info("Applied chat {} between {} and {} on Stream", action, blockerId, blockedId);
    } catch (Exception e) {
      log.error(
          "Could not {} the pair {}/{} on Stream Chat; the block still applies in this app but the"
              + " two can still reach each other in chat",
          action,
          blockerId,
          blockedId,
          e);
    }
  }

  /**
   * Pushes the caller <em>and every friend of theirs</em> to Stream in one call.
   *
   * <p>Stream only knows a user once someone has upserted them, and it refuses to create a channel
   * whose members it has never seen ("The following users are involved in channel create operation,
   * but don't exist"). Syncing only the caller therefore left them unable to start a conversation
   * with anyone who had not yet opened chat themselves. Beyond that circle, the frontend asks for a
   * specific counterpart via {@link #ensureChatParticipants}.
   *
   * <p>Done synchronously, on purpose: pushing it to {@code @Async} reintroduces the race this
   * fixes — the frontend receives its token and can create the channel before the upsert lands. The
   * work is bounded by one user's friend count; if it ever gets slow, remember the last sync time
   * (Redis) rather than making it asynchronous.
   *
   * <p>A failed profile sync degrades the chat UI to numeric ids; a failed token blocks chat
   * entirely. Never let the former cause the latter — Stream lazily creates the user on {@code
   * connectUser} anyway.
   *
   * <p><b>Blocked users are left out, and on its own that was never a defence.</b> Skipping them
   * here only means this backend does not introduce the two of them to Stream; the frontend holds a
   * Stream user token and talks to Stream directly, so once Stream knows both users it honours a
   * channel create between them without asking this server. The actual enforcement now lives in
   * {@link #applyBlock}, which tells Stream about the block. This filter stays because there is no
   * reason to push a blocked person's name and avatar to a chat backend that must refuse to use
   * them.
   */
  private void syncProfilesBestEffort(UserEntity user) {
    try {
      List<UserEntity> toSync = new ArrayList<>();
      toSync.add(user);

      Set<Integer> blockedIds = blockQueryService.blockedPairIds(user.getId());
      List<Integer> friendIds =
          friendshipService.getFriendIds(user.getId()).stream()
              .filter(id -> !blockedIds.contains(id))
              .toList();
      if (!friendIds.isEmpty()) {
        // findAllById() silently skips ids missing from Postgres, which is exactly what we want
        // for friend edges left dangling in Neo4j by a deleted account.
        userRepository
            .findAllById(friendIds)
            .forEach(
                friend -> {
                  if (!friend.getId().equals(user.getId())) {
                    toSync.add(friend);
                  }
                });
      }

      streamChatClient.upsertUsers(toSync);
      log.info("Synced {} user(s) to Stream Chat for user {}", toSync.size(), user.getId());
    } catch (Exception e) {
      log.warn(
          "Could not sync user {} and friends to Stream Chat; issuing token anyway",
          user.getId(),
          e);
    }
  }

  /**
   * Creates a named group channel with the caller as its owner.
   *
   * <p>The pair endpoint could get a group open — call it once per member, then let the browser
   * create the channel — and that is what a frontend has to do without this. It costs one request
   * per member, and it leaves two things wrong that no amount of client code can fix. The browser
   * decides the channel's owner, because {@code created_by_id} is whatever the client sends. And
   * the only blocks checked are the ones involving the caller: invite two people who have blocked
   * each other and the group is created anyway, putting them in a room together, which is the one
   * outcome blocking exists to prevent.
   *
   * <p>So the whole membership is settled here, in one pass: one lookup for existence, one for
   * blocks across every pair, one upsert for the batch, one channel create.
   *
   * <p><b>The caller is not asked for.</b> They are added as a member and named as owner from the
   * authenticated principal, so a request cannot build a group it is not part of, nor hand
   * ownership to someone else.
   *
   * @throws ValidationException if the group is too small once duplicates and the caller are
   *     removed, or if a block stands between any two members
   * @throws NotFoundException if any member id has no user behind it
   */
  // No @Transactional, for the same reason as ensureChatParticipants: the database work is two
  // self-managing reads, and holding a connection across the two HTTP round trips to Stream buys
  // no isolation — the channel create is not undone by a rollback either way.
  public GroupChatResponse createGroupChat(
      Integer callerId, String name, String imageUrl, List<Integer> requestedMemberIds) {
    if (!properties.isConfigured()) {
      throw new MissingConfigurationException(
          "Stream Chat is not configured (missing stream.chat.api-key/api-secret); cannot create a"
              + " group chat");
    }

    // LinkedHashSet, not a plain distinct(): the frontend's member picker can legitimately produce
    // the same person twice, and the caller may or may not have included themselves. Order is kept
    // so the response lists members the way they were chosen.
    LinkedHashSet<Integer> otherIds = new LinkedHashSet<>(requestedMemberIds);
    otherIds.remove(callerId);

    if (otherIds.size() < MIN_OTHER_MEMBERS) {
      // Deliberately after de-duplication rather than as a @Size on the DTO, which cannot see that
      // [7, 7] or [7, callerId] is one person. The bean validation ceiling still applies first and
      // keeps an absurd list from reaching the database.
      throw new ValidationException(
          "A group needs at least " + MIN_OTHER_MEMBERS + " other people besides yourself");
    }

    LinkedHashSet<Integer> allIds = new LinkedHashSet<>();
    allIds.add(callerId);
    allIds.addAll(otherIds);

    List<UserEntity> members = userRepository.findAllById(allIds);
    if (members.size() != allIds.size()) {
      Set<Integer> found = members.stream().map(UserEntity::getId).collect(Collectors.toSet());
      List<Integer> missing = allIds.stream().filter(id -> !found.contains(id)).toList();
      throw new NotFoundException("User not found with ID: " + missing);
    }

    rejectIfAnyPairIsBlocked(callerId, allIds);

    streamChatClient.upsertUsers(members);

    // Random rather than derived from the membership: two people can want two different groups
    // with the same members, and a derived id would silently hand the second one the first one's
    // channel — Stream's create verb is get-or-create, so it would not even error.
    String channelId = "grp-" + UUID.randomUUID();
    streamChatClient.createGroupChannel(channelId, name, imageUrl, callerId, allIds);

    log.info(
        "Created Stream group channel {} with {} member(s) for user {}",
        channelId,
        allIds.size(),
        callerId);

    return GroupChatResponse.builder()
        .channelType(StreamChatClient.GROUP_CHANNEL_TYPE)
        .channelId(channelId)
        .cid(StreamChatClient.GROUP_CHANNEL_TYPE + ":" + channelId)
        .name(name)
        .memberIds(allIds.stream().map(String::valueOf).toList())
        .createdBy(String.valueOf(callerId))
        .build();
  }

  /**
   * Refuses a membership that puts two blocked people in one room.
   *
   * <p>What may be said about it differs by who is involved. A block between the caller and someone
   * they picked is already discoverable one pair at a time through {@code ensureChatParticipants},
   * so naming those ids tells them nothing new and lets the member picker mark them. A block
   * between two <em>other</em> members is a fact about two third parties; saying which two would
   * hand anyone a way to probe for blocks between people they know, by building groups and reading
   * the error. That case gets a message that names nobody — awkward for whoever is trying to
   * assemble the group, and the right trade.
   *
   * <p>Neither message says "blocked", following the same rule as the friend-request rejection.
   */
  private void rejectIfAnyPairIsBlocked(Integer callerId, Set<Integer> allIds) {
    List<UserBlockId> blocks = blockQueryService.blocksAmong(allIds);
    if (blocks.isEmpty()) {
      return;
    }

    // TreeSet so the ids come out in a stable order; the message is asserted on in tests and read
    // by a human in a support ticket.
    Set<Integer> unreachable =
        blocks.stream()
            .filter(b -> b.getBlockerId().equals(callerId) || b.getBlockedId().equals(callerId))
            .map(b -> b.getBlockerId().equals(callerId) ? b.getBlockedId() : b.getBlockerId())
            .collect(Collectors.toCollection(TreeSet::new));

    if (!unreachable.isEmpty()) {
      throw new ValidationException(
          "You cannot start a conversation with these users: " + unreachable);
    }
    throw new ValidationException("Some of the people you selected cannot be in the same group");
  }
}
