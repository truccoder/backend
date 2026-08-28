package com.socialapp.posts.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.common.utils.Constants;
import com.socialapp.posts.dto.PostPageResponseDto;
import com.socialapp.posts.service.PostQueryService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/**
 * The posts section of somebody's profile.
 *
 * <p>Lives under {@code /v1/api/users/{userId}/…} rather than under {@code /v1/api/posts} because
 * the subject of the request is the user, not a post — the same shape {@code ReputationController}
 * and the public profile already use, so a profile screen fetches all its sections from one path
 * prefix.
 *
 * <p>What comes back depends on who is asking: a stranger sees only PUBLIC posts, a friend also
 * sees FRIENDS posts, and the author additionally sees their PRIVATE posts and the ones still in
 * (or rejected by) moderation.
 */
@RestController
@RequestMapping("/v1/api/users/{userId}/posts")
@RequiredArgsConstructor
public class UserPostsController {
  private final PostQueryService postQueryService;

  @GetMapping
  public PostPageResponseDto getUserPosts(
      @PathVariable Integer userId,
      @RequestParam(required = false) Integer cursor,
      @RequestParam(defaultValue = "20") @Positive @Max(Constants.MAX_PAGINATION_PAGE_SIZE)
          int limit) {
    // Open to guests — a null viewer is the "stranger" level, see PostVisibilityService.
    return postQueryService.getPostsByAuthor(
        SecurityUtils.getCurrentUserIdOrNull(), userId, cursor, limit);
  }
}
