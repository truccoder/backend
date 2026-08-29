package com.socialapp.search.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.friendships.service.FriendshipService;
import com.socialapp.posts.service.MentionScanner;
import com.socialapp.search.dto.MentionSuggestionDto;
import com.socialapp.search.util.SearchQuerySanitizer;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * The list behind the {@code @} in a composer: who the caller can tag, friends first.
 *
 * <p>Its own service rather than a branch of {@link SuggestService}, which is the search box's
 * type-ahead. The two look like the same query and answer opposite questions. The search box is
 * guessing a destination, so it mixes people with books, ranks nobody above anybody, and refuses
 * the Neo4j round trip that friend-ranking costs. This box already knows the answer is a person and
 * that the person is usually someone the caller knows — so it pays for the friend ids, drops books
 * entirely, and returns the handle as a field the client can insert rather than as a label.
 *
 * <p>Fired per keystroke like the other one, but only after an {@code @}, and the clients debounce.
 * Two round trips per call (friend ids from Neo4j, users from Postgres) is the price of the
 * ranking, and it is the reason this ordering was never simply added to {@code UserRepository
 * .suggest}.
 *
 * <p>The two shapes of answer:
 *
 * <ul>
 *   <li><b>Nothing typed yet</b> ({@code @} alone) — friends only. Everyone else would be a
 *       user-directory dump; see {@code UserRepository#findMentionableFriends}.
 *   <li><b>Something typed</b> — everyone matching, friends first. People do tag those they have
 *       not friended, and a query narrow enough to name someone is not a directory dump.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class MentionSuggestService {

  /**
   * How many rows to fetch per row returned.
   *
   * <p>{@link MentionScanner#isMentionable} filters the candidates after the query, so a handle the
   * scanner would not find back in the finished comment costs the dropdown a row. Fetching double
   * means the list still fills for anything short of half the matches being untaggable, and costs
   * nothing measurable: these are single-digit limits against an indexed query.
   */
  private static final int OVERFETCH = 2;

  private final BlockQueryService blockQueryService;
  private final FriendshipService friendshipService;
  private final UserRepository userRepository;

  /**
   * People the caller may tag, at most {@code limit} of them.
   *
   * @param query what has been typed after the {@code @}, blank for the moment just after it
   * @param limit the ceiling on rows; the controller caps it
   * @param currentUserId the caller, never suggested to themselves
   */
  public List<MentionSuggestionDto> suggest(String query, int limit, Integer currentUserId) {
    List<Integer> friendIds = friendshipService.getFriendIds(currentUserId);
    Set<Integer> friendIdSet = Set.copyOf(friendIds);

    String sanitized = SearchQuerySanitizer.sanitize(stripLeadingAt(query));
    PageRequest page = PageRequest.of(0, limit * OVERFETCH);

    List<UserEntity> candidates =
        sanitized.isEmpty()
            ? userRepository.findMentionableFriends(
                safeIds(friendIds), excludedIds(currentUserId), page)
            : userRepository.suggestMentions(
                sanitized, safeIds(friendIds), excludedIds(currentUserId), page);

    return candidates.stream()
        // A handle the scanner cannot find is a tag that notifies nobody, so the row is worse than
        // no row: the client renders it as a working mention and the person never hears about it.
        .filter(user -> MentionScanner.isMentionable(user.getUsername()))
        .limit(limit)
        .map(
            user ->
                new MentionSuggestionDto(
                    user.getId(),
                    user.getUsername(),
                    user.getFullName(),
                    user.getProfilePictureUrl(),
                    friendIdSet.contains(user.getId())))
        .toList();
  }

  /**
   * Drops an {@code @} the client left on the front of the query.
   *
   * <p>Both readings of "what has been typed after the {@code @}" are in the wild — some composers
   * send the token they matched, some send it with its sigil — and a leading {@code @} that reached
   * the LIKE pattern would match nobody, because no stored handle contains one. Cheaper to accept
   * both than to make every client agree.
   */
  private String stripLeadingAt(String query) {
    return query != null && query.startsWith("@") ? query.substring(1) : query;
  }

  /**
   * Who must never appear in the dropdown: the caller, plus their block set in both directions.
   *
   * <p>The caller, because naming yourself in your own comment is not something to be offered — and
   * {@code CommentService#notifyMentionedUsers} drops such a mention anyway, so the row could only
   * produce a tag with no notification behind it.
   *
   * <p>The block set, for the reason {@code SuggestService} gives: a box that completes the name of
   * someone who blocked the viewer hands back exactly the link the block exists to take away.
   * Blocking already ends the friendship, so a blocked user cannot arrive through the friends
   * branch either; this stays as the reader-side half of a rule worth enforcing twice.
   *
   * <p>Never empty, so {@code NOT IN ()} — which is not valid SQL — cannot be generated: the
   * caller's own id is always in it, and a sentinel stands in for the id of a caller who somehow
   * has none.
   */
  private Collection<Integer> excludedIds(Integer currentUserId) {
    Set<Integer> excluded = new HashSet<>(blockQueryService.blockedPairIds(currentUserId));
    excluded.add(Objects.requireNonNullElse(currentUserId, -1));
    return excluded;
  }

  /** Same never-empty rule for the friend ids, which are matched with {@code IN} rather than out. */
  private Collection<Integer> safeIds(List<Integer> friendIds) {
    return friendIds.isEmpty() ? List.of(-1) : friendIds;
  }
}
