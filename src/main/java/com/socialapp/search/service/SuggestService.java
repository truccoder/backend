package com.socialapp.search.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.bookstore.repository.BookRepository;
import com.socialapp.bookstore.service.BookStorageService;
import com.socialapp.search.dto.SuggestionDto;
import com.socialapp.search.dto.SuggestionType;
import com.socialapp.search.util.SearchQuerySanitizer;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * The search box's type-ahead dropdown — fired once per keystroke.
 *
 * <p>Split out of {@link SearchService} rather than living beside it, because the two answer
 * different questions under different budgets. The results page may take its time: it ranks friends
 * first (which costs a Neo4j round trip for the viewer's friend ids), counts total hits for the
 * pager, and hydrates every post with its author, its book and six format-detail blobs. None of
 * that is affordable per character typed, so none of it happens here — and keeping the two in one
 * class made it far too easy to "reuse" one of those helpers and quietly put a graph query on the
 * keystroke path.
 */
@Service
@RequiredArgsConstructor
public class SuggestService {

  private final BlockQueryService blockQueryService;
  private final UserRepository userRepository;
  private final BookRepository bookRepository;
  private final BookStorageService bookStorageService;

  /**
   * A short flat list of people and books.
   *
   * <p>People come before books. Both lists are filled to {@code limit} and the whole thing trimmed
   * afterwards, so a query matching only books still fills the dropdown.
   */
  public List<SuggestionDto> suggest(String query, int limit, Integer currentUserId) {
    String sanitized = SearchQuerySanitizer.sanitize(query);
    // Same block rule as /search. A dropdown that completes the name of someone who blocked the
    // viewer hands back exactly the link the block exists to take away.
    Collection<Integer> excluded = excludedIds(blockQueryService.blockedPairIds(currentUserId));
    PageRequest page = PageRequest.of(0, limit);

    List<SuggestionDto> suggestions = new ArrayList<>(limit);
    userRepository.suggest(sanitized, excluded, page).stream()
        .map(
            u ->
                new SuggestionDto(
                    SuggestionType.USER,
                    u.getId(),
                    u.getFullName(),
                    u.getUsername() == null ? null : "@" + u.getUsername(),
                    u.getProfilePictureUrl()))
        .forEach(suggestions::add);

    bookRepository.suggestByTitle(sanitized, page).stream()
        .map(
            b ->
                new SuggestionDto(
                    SuggestionType.BOOK,
                    b.getId(),
                    b.getTitle(),
                    null,
                    bookStorageService.getCoverUrl(b.getCoverImageKey())))
        .forEach(suggestions::add);

    return suggestions.stream().limit(limit).toList();
  }

  /**
   * The caller's block set, never empty: {@code NOT IN ()} is not valid SQL and having blocked
   * nobody is the normal case, so an empty set becomes a sentinel id no user can have.
   */
  private Collection<Integer> excludedIds(Set<Integer> blockedIds) {
    return blockedIds.isEmpty() ? List.of(-1) : blockedIds;
  }
}
