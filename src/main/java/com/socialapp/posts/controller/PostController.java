package com.socialapp.posts.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.common.utils.Constants;
import com.socialapp.newsfeed.dto.FeedPostDataDto;
import com.socialapp.posts.dto.CreatePostRequestDto;
import com.socialapp.posts.dto.CreatePostResponseDto;
import com.socialapp.posts.dto.PostPageResponseDto;
import com.socialapp.posts.dto.UpdatePostRequestDto;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.service.PostQueryService;
import com.socialapp.posts.service.PostService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/posts")
@RequiredArgsConstructor
public class PostController {
  private final PostService postService;
  private final PostQueryService postQueryService;

  /**
   * Returns the created post's id and moderation status instead of {@code void}, so the composer
   * can navigate straight to the permalink it just published and know whether to render the post or
   * a "pending review" state — FE's {@code docs/backend-plan.md} B39.
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public CreatePostResponseDto createPost(@Valid @RequestBody CreatePostRequestDto request) {
    PostEntity post = postService.createPost(SecurityUtils.getCurrentUserId(), request);
    return CreatePostResponseDto.builder()
        .postId(post.getId())
        .moderationStatus(post.getModerationStatus())
        .build();
  }

  /**
   * The discovery feed: everyone's public posts.
   *
   * <p>Declared before {@code /{postId}} on purpose. Spring maps the literal segment ahead of the
   * template regardless of declaration order, so this is documentation rather than load-bearing —
   * but a reader scanning the file should see immediately that {@code /posts/public} is not a post
   * whose id is "public".
   */
  @GetMapping("/public")
  public PostPageResponseDto getPublicFeed(
      @RequestParam(required = false) Integer cursor,
      @RequestParam(required = false) String hashtag,
      @RequestParam(defaultValue = "20") @Positive @Max(Constants.MAX_PAGINATION_PAGE_SIZE)
          int limit) {
    // OrNull, not getCurrentUserId(): this endpoint is open to guests, and the throwing variant
    // would turn an allowed anonymous request into a 401 after Spring Security let it through.
    // hashtag is what makes a hashtag badge on a post card clickable (B31) — null means unfiltered.
    return postQueryService.getPublicFeed(
        SecurityUtils.getCurrentUserIdOrNull(), cursor, hashtag, limit);
  }

  /** Permalink. A post the caller may not see is reported as missing — see PostQueryService. */
  @GetMapping("/{postId}")
  public FeedPostDataDto getPost(@PathVariable Integer postId) {
    return postQueryService.getPost(SecurityUtils.getCurrentUserIdOrNull(), postId);
  }

  @PostMapping(value = "/books", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public CreatePostResponseDto createBookPost(
      @Valid @RequestPart("metadata") CreatePostRequestDto request,
      @RequestPart("file") MultipartFile bookFile,
      @RequestPart(value = "cover", required = false) MultipartFile coverFile) {
    PostEntity post =
        postService.createBookPost(SecurityUtils.getCurrentUserId(), request, bookFile, coverFile);
    return CreatePostResponseDto.builder()
        .postId(post.getId())
        .moderationStatus(post.getModerationStatus())
        .build();
  }

  @PutMapping("/{postId}")
  public void updatePost(
      @PathVariable Integer postId, @Valid @RequestBody UpdatePostRequestDto request) {
    postService.updatePost(SecurityUtils.getCurrentUserId(), postId, request);
  }

  @DeleteMapping("/{postId}")
  public void deletePost(@PathVariable Integer postId) {
    postService.deletePost(SecurityUtils.getCurrentUserId(), postId);
  }

  // POST, not PATCH: state-transition actions are POST project-wide (friend requests, project
  // applications, skill verifications), and this is not a partial update of anything. The path is
  // left as-is so it stays the sibling of the DELETE below.
  @PostMapping("/{postId}/qna/accept-answer/{commentId}")
  public void acceptAnswer(@PathVariable Integer postId, @PathVariable Integer commentId) {
    postService.acceptAnswer(SecurityUtils.getCurrentUserId(), postId, commentId);
  }

  /**
   * Takes back the accepted answer. No comment id in the path: a post has at most one accepted
   * answer, so the post alone identifies what is being undone. Switching answers is this call
   * followed by {@link #acceptAnswer} — {@code acceptAnswer} refuses to overwrite a pick that is
   * still standing.
   */
  @DeleteMapping("/{postId}/qna/accept-answer")
  public void unacceptAnswer(@PathVariable Integer postId) {
    postService.unacceptAnswer(SecurityUtils.getCurrentUserId(), postId);
  }
}
