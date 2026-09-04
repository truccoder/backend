package com.socialapp.search.dto;

import java.util.List;

import com.socialapp.matchmaking.dto.ProjectResponseDto;
import com.socialapp.roadmap.dto.RoadmapDto;

/**
 * One search, five result lists — the five things this product claims to be searchable.
 *
 * <p>{@code books} is a list in its own right <b>as well as</b> inline on the post that carries it.
 * That looks like duplication and is not: a matching book still surfaces through {@code posts} with
 * its {@code book} attached, because a book here is published as a post and the post is what a
 * reader reacts to and comments on. The separate list exists because "find me the book" and "find
 * me what people said" are different questions, and a reader who typed a title wants a shelf, not a
 * timeline that happens to contain one. Both lists are filtered by the same visibility and block
 * rules, over the same underlying query — see {@code SearchService.searchBooks}.
 *
 * <p>{@code projects} and {@code roadmaps} were added for backend-plan B33: the search page grew
 * "Dự án" and "Lộ trình" tabs that had to filter the domains' list endpoints client-side because
 * this response stopped at people/posts/books. They are served by {@code
 * ProjectQueryService.searchProjects} and {@code RoadmapService.searchRoadmaps} — the same DTOs
 * those domains' own read endpoints return, so the frontend reuses its existing types — rather
 * than by {@code SearchService}, whose branches all block-filter and these deliberately do not.
 */
public record SearchResponse(
    List<UserDto> users,
    List<PostDto> posts,
    List<BookDto> books,
    List<ProjectResponseDto> projects,
    List<RoadmapDto> roadmaps) {}
