package com.socialapp.search.dto;

import java.util.List;

/**
 * Books never appear as their own list — a matching book surfaces as its linked post (with book
 * info attached inline) inside {@code posts} instead. See {@code
 * SearchService.searchPostsWithBookInfo}.
 */
public record SearchResponse(List<UserDto> users, List<PostDto> posts) {}
