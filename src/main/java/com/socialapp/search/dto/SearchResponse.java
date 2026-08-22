package com.socialapp.search.dto;

import java.util.List;

/**
 * One search, three result lists — the three things this product claims to be searchable.
 *
 * <p>{@code books} is a list in its own right <b>as well as</b> inline on the post that carries it.
 * That looks like duplication and is not: a matching book still surfaces through {@code posts} with
 * its {@code book} attached, because a book here is published as a post and the post is what a
 * reader reacts to and comments on. The separate list exists because "find me the book" and "find
 * me what people said" are different questions, and a reader who typed a title wants a shelf, not a
 * timeline that happens to contain one. Both lists are filtered by the same visibility and block
 * rules, over the same underlying query — see {@code SearchService.searchBooks}.
 */
public record SearchResponse(List<UserDto> users, List<PostDto> posts, List<BookDto> books) {}
