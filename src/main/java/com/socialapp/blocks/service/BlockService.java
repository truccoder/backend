package com.socialapp.blocks.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.socialapp.blocks.entity.UserBlockEntity;
import com.socialapp.blocks.entity.UserBlockId;
import com.socialapp.blocks.repository.UserBlockRepository;
import com.socialapp.chat.service.StreamChatService;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.friendships.repository.FriendRequestRepository;
import com.socialapp.friendships.service.FriendshipService;
import com.socialapp.security.dto.PublicUserResponse;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/** The write side of blocking: block, unblock, and list who you have blocked. */
@Service
@RequiredArgsConstructor
public class BlockService {

  private final UserBlockRepository userBlockRepository;
  private final UserRepository userRepository;
  private final FriendshipService friendshipService;
  private final FriendRequestRepository friendRequestRepository;

  // blocks -> chat, and chat -> blocks via BlockQueryService. Not a cycle: BlockQueryService was
  // split off precisely so the read side has no dependencies (see its class comment), so the two
  // beans do not close a loop.
  private final StreamChatService streamChatService;

  /**
   * Blocks a user, and severs whatever connection the two already had.
   *
   * <p>The severing is not a bonus feature, it is what makes the block mean anything: a block that
   * left the friendship standing would leave the blocked user inside the friends-only audience of
   * every post, still counted as a friend, still reachable — the block would hide them from one
   * feed and change nothing else. A pending request in either direction is cancelled for the same
   * reason: left alive, it is a one-tap route back to being friends with someone who just blocked
   * you.
   *
   * <p>Idempotent: blocking someone already blocked changes nothing and does not fail. The unique
   * pair is the primary key, so a duplicate would otherwise surface as a constraint violation on a
   * request that asked for a state already true.
   */
  @Transactional
  public void block(Integer blockerId, Integer blockedId) {
    if (blockerId.equals(blockedId)) {
      throw new ValidationException("You cannot block yourself");
    }
    if (!userRepository.existsById(blockedId)) {
      throw new NotFoundException("User not found with ID: " + blockedId);
    }

    UserBlockId id = new UserBlockId(blockerId, blockedId);
    if (!userBlockRepository.existsById(id)) {
      UserBlockEntity entity = new UserBlockEntity();
      entity.setId(id);
      userBlockRepository.save(entity);
    }

    // Runs even when the block already existed: cheap, and it repairs a pair left half-severed by
    // an earlier partial failure rather than leaving that state unreachable.
    friendshipService.unfriend(blockerId, blockedId);
    friendRequestRepository.cancelPendingBetween(blockerId, blockedId);

    afterCommit(() -> streamChatService.applyBlock(blockerId, blockedId));
  }

  /**
   * Lifts a block. Idempotent for the same reason {@link #block} is.
   *
   * <p>Deliberately does not restore the friendship it ended — that friendship was deleted, not
   * suspended, and resurrecting it would put someone back inside a friends-only audience without
   * either person agreeing to it a second time.
   */
  @Transactional
  public void unblock(Integer blockerId, Integer blockedId) {
    UserBlockId id = new UserBlockId(blockerId, blockedId);
    // Guarded rather than relying on deleteById's behaviour for a missing row: that behaviour has
    // changed across Spring Data versions (it used to throw), and idempotency here is a contract
    // this endpoint promises, not an implementation detail to inherit.
    if (userBlockRepository.existsById(id)) {
      userBlockRepository.deleteById(id);
    }

    afterCommit(() -> streamChatService.liftBlock(blockerId, blockedId));
  }

  /**
   * Runs {@code action} once the surrounding transaction has committed, or immediately if there is
   * no transaction.
   *
   * <p>Two reasons the Stream calls are not made inline. Ordering: the call reaches an external
   * service that could act on it before this transaction commits — or after it rolls back, leaving
   * a block on Stream that does not exist here. Connection hold time: an HTTP round trip to
   * Stream's servers inside the transaction keeps a database connection checked out for its
   * duration, on a request that has already done all the database work it needs.
   */
  private void afterCommit(Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      action.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.run();
          }
        });
  }

  /** Who the caller has blocked, newest first. Not the reverse direction — that is not theirs. */
  @Transactional(readOnly = true)
  public List<PublicUserResponse> getBlockedUsers(Integer blockerId) {
    List<Integer> blockedIds =
        userBlockRepository.findByIdBlockerIdOrderByCreatedAtDesc(blockerId).stream()
            .map(block -> block.getId().getBlockedId())
            .toList();

    if (blockedIds.isEmpty()) {
      return List.of();
    }

    // findAllById does not preserve the requested order, so the rows are re-sorted back into the
    // blocked-at order the ids came in; a list that reshuffles between calls is unusable in a UI.
    Map<Integer, UserEntity> usersById =
        userRepository.findAllById(blockedIds).stream()
            .collect(Collectors.toMap(UserEntity::getId, Function.identity()));

    return blockedIds.stream()
        .map(usersById::get)
        .filter(Objects::nonNull)
        .map(PublicUserResponse::from)
        .toList();
  }
}
