package com.socialapp.search.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.common.utils.Constants;
import com.socialapp.search.document.PostDocument;
import com.socialapp.search.document.UserDocument;
import com.socialapp.search.dto.SearchResult;
import com.socialapp.search.dto.UnifiedSearchResponse;
import com.socialapp.search.service.FriendshipQueryService;
import com.socialapp.search.service.SearchService;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@Validated
@RestController
@RequestMapping("/v1/api/search")
@RequiredArgsConstructor
public class SearchController {
  private final SearchService searchService;
  private final FriendshipQueryService friendshipQueryService;

  @GetMapping
  public UnifiedSearchResponse searchAll(
      @RequestParam @NotBlank String q,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_SEARCH_PAGE_SIZE) @Positive
          int size) {
    Integer currentUserId = getCurrentUserId();
    List<Integer> friendIds = friendshipQueryService.getFriendIds(currentUserId);
    return searchService.searchAll(q, size, currentUserId, friendIds);
  }

  @GetMapping("/users")
  public SearchResult<UserDocument> searchUsers(
      @RequestParam @NotBlank String q,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE) @Positive int page,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_SEARCH_PAGE_SIZE) @Positive
          int size) {
    Integer currentUserId = getCurrentUserId();
    List<Integer> friendIds = friendshipQueryService.getFriendIds(currentUserId);
    return searchService.searchUsers(q, page, size, friendIds);
  }

  @GetMapping("/posts")
  public SearchResult<PostDocument> searchPosts(
      @RequestParam @NotBlank @Size(min = 2) String q,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE) @Positive int page,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_SEARCH_PAGE_SIZE) @Positive
          int size) {
    Integer currentUserId = getCurrentUserId();
    List<Integer> friendIds = friendshipQueryService.getFriendIds(currentUserId);
    return searchService.searchPosts(q, page, size, currentUserId, friendIds);
  }

  // TODO: replace with SecurityContextHolder once auth module is ready
  private Integer getCurrentUserId() {
    // JwtTokenAuthenticationHolder
    return 0;
  }
}
