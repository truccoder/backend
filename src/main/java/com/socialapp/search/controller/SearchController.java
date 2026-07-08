package com.socialapp.search.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.common.utils.Constants;
import com.socialapp.search.dto.PostDto;
import com.socialapp.search.dto.SearchResponse;
import com.socialapp.search.dto.UserDto;
import com.socialapp.search.service.FriendshipQueryService;
import com.socialapp.search.service.SearchService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/**
 * Single-call unified search, directly against Postgres (t_users, t_posts, t_books) — no search
 * index. A book match doesn't get its own list: it surfaces as its linked post, with book info
 * attached inline.
 */
@Validated
@RestController
@RequestMapping("/v1/api/search")
@RequiredArgsConstructor
public class SearchController {
  private final SearchService searchService;
  private final FriendshipQueryService friendshipQueryService;

  @GetMapping
  public SearchResponse search(
      @RequestParam @NotBlank String q,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_SEARCH_PAGE_SIZE) @Positive
          int size) {
    Integer currentUserId = SecurityUtils.getCurrentUserId();
    List<Integer> friendIds = friendshipQueryService.getFriendIds(currentUserId);

    List<UserDto> users = searchService.searchUsers(q, 1, size, friendIds).getItems();
    List<PostDto> posts = searchService.searchPostsWithBookInfo(q, size, currentUserId, friendIds);

    return new SearchResponse(users, posts);
  }
}
