package com.socialapp.blocks.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.blocks.repository.UserBlockRepository;

import lombok.RequiredArgsConstructor;

/**
 * The read side of blocking: the one helper every other module asks "who must this user not see?".
 *
 * <p>Split from {@link BlockService} because blocking a user cascades into unfriending them, so
 * {@code BlockService} depends on the friendships module — while the friendships module itself has
 * to filter blocked users out of its suggestions. Keeping the query side dependency-free (it talks
 * only to its own repository) is what stops that from being a circular dependency, and it is also
 * the class that anything doing filtering should inject.
 *
 * <p><b>Not cached.</b> Every other per-user lookup in this codebase caches in Redis; this one
 * deliberately does not. A stale friend suggestion is a cosmetic bug, whereas a stale block set
 * means content from someone a user has just blocked keeps arriving — which is the exact failure
 * the feature exists to prevent. The lookup is a two-column indexed read; if it ever shows up in a
 * profile, cache it with explicit eviction in {@link BlockService#block} and {@code unblock}, not
 * with a TTL.
 */
@Service
@RequiredArgsConstructor
public class BlockQueryService {

  private final UserBlockRepository userBlockRepository;

  /**
   * Every user id that must be filtered out of what {@code userId} sees.
   *
   * <p>Both directions in one set: people {@code userId} blocked, and people who blocked {@code
   * userId}. See {@code UserBlockRepository#findBlockerIds} for why the second half is not
   * optional.
   *
   * <p>Returns an empty set for the overwhelmingly common case of a user who has no blocks at all,
   * and callers building SQL from it must handle that — {@code IN ()} is a syntax error and {@code
   * NOT IN ()} silently matches nothing in some dialects.
   */
  @Transactional(readOnly = true)
  public Set<Integer> blockedPairIds(Integer userId) {
    // A guest (no id) cannot have blocked anyone and cannot have been blocked. Answered without
    // touching the database, so the endpoints that guests may read do not pay two queries per
    // request to be told nothing — and so a null never reaches the repository as a parameter.
    if (userId == null) {
      return Set.of();
    }

    List<Integer> blocked = userBlockRepository.findBlockedIds(userId);
    List<Integer> blockers = userBlockRepository.findBlockerIds(userId);

    Set<Integer> ids = new HashSet<>(blocked.size() + blockers.size());
    ids.addAll(blocked);
    ids.addAll(blockers);
    return ids;
  }

  /**
   * Whether a block stands between two users, in either direction.
   *
   * <p>For the pair-at-a-time checks (may these two chat, may this notification be delivered)
   * where pulling the whole block set would be wasteful.
   */
  @Transactional(readOnly = true)
  public boolean isBlockedEitherWay(Integer userId, Integer otherUserId) {
    if (userId == null || otherUserId == null) {
      return false;
    }
    return userBlockRepository.existsBetween(userId, otherUserId);
  }

  /**
   * The same question for a list of candidates, as one query — for filtering a page of people
   * (search results, friend suggestions) without a query per row.
   */
  @Transactional(readOnly = true)
  public Set<Integer> filterOutBlocked(Integer userId, List<Integer> candidateIds) {
    if (candidateIds.isEmpty()) {
      return Set.of();
    }
    Set<Integer> blocked = blockedPairIds(userId);
    Set<Integer> allowed = new HashSet<>(candidateIds);
    allowed.removeAll(blocked);
    return allowed;
  }
}
