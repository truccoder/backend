package com.socialapp.newsfeed.service;

import static com.socialapp.newsfeed.service.PostScoringService.FEED_KEY_PREFIX;
import static com.socialapp.newsfeed.service.PostScoringService.POST_CACHE_KEY_PREFIX;

import java.time.Duration;
import java.util.*;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.newsfeed.dto.FeedPostDataDto;
import com.socialapp.newsfeed.dto.FeedResponseDto;
import com.socialapp.newsfeed.entity.UserInteractionEntity;
import com.socialapp.newsfeed.entity.enums.InteractionType;
import com.socialapp.newsfeed.repository.UserInteractionRepository;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.PostTagEntity;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.search.service.FriendshipQueryService;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsfeedService {
  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final FriendshipQueryService friendshipQueryService;
  private final UserInteractionRepository userInteractionRepository;
  private final PostRepository postRepository;
  private final UserRepository userRepository;

  private static final int MAX_FEED_SIZE = 1000;
  private static final Duration POST_CACHE_TTL = Duration.ofDays(7);

  public void fanOutPost(Integer postId) {
    PostEntity post =
        postRepository
            .findById(postId)
            .orElseThrow(() -> new NotFoundException("Post not found: " + postId));

    UserEntity author =
        userRepository
            .findById(post.getAuthorId())
            .orElseThrow(() -> new NotFoundException("Author not found: " + post.getAuthorId()));

    List<Integer> taggedUserIds =
        Objects.isNull(post.getTags())
            ? List.of()
            : post.getTags().stream().map(PostTagEntity::getTaggedUserId).toList();

    FeedPostDataDto postData =
        FeedPostDataDto.builder()
            .postId(post.getId())
            .authorId(post.getAuthorId())
            .authorFullName(author.getFullName())
            .authorProfilePictureUrl(author.getProfilePictureUrl())
            .content(post.getContent())
            .visibility(post.getVisibility())
            .createdAt(post.getCreatedAt())
            .build();

    fanOutPost(postData, taggedUserIds);
  }

  public void fanOutPost(FeedPostDataDto postData, List<Integer> taggedUserIds) {
    String postId = String.valueOf(postData.getPostId());
    double score =
        postData.getCreatedAt() != null
            ? postData.getCreatedAt().toInstant().toEpochMilli()
            : java.time.Instant.now().toEpochMilli();

    cachePostData(postId, postData);
    addToFeed(postData.getAuthorId(), postId, score);

    if (!PostVisibility.PRIVATE.equals(postData.getVisibility())) {
      List<Integer> friendIds = friendshipQueryService.getFriendIds(postData.getAuthorId());
      for (Integer friendId : friendIds) {
        addToFeed(friendId, postId, score);
      }
    }

    if (Objects.nonNull(taggedUserIds)) {
      for (Integer taggedUserId : taggedUserIds) {
        addToFeed(taggedUserId, postId, score);
      }
    }

    log.debug("Fan-out post {} (visibility={})", postData.getPostId(), postData.getVisibility());
  }

  public void updatePostCache(FeedPostDataDto postData) {
    cachePostData(String.valueOf(postData.getPostId()), postData);
  }

  public void removePost(Integer postId, Integer authorId, List<Integer> taggedUserIds) {
    String postIdStr = String.valueOf(postId);

    redisTemplate.delete(POST_CACHE_KEY_PREFIX + postIdStr);
    removeFromFeed(authorId, postIdStr);

    List<Integer> friendIds = friendshipQueryService.getFriendIds(authorId);
    for (Integer friendId : friendIds) {
      removeFromFeed(friendId, postIdStr);
    }

    if (!CollectionUtils.isEmpty(taggedUserIds)) {
      for (Integer taggedUserId : taggedUserIds) {
        removeFromFeed(taggedUserId, postIdStr);
      }
    }
  }

  public FeedResponseDto getFeed(Integer userId, int page, int size) {
    String feedKey = FEED_KEY_PREFIX + userId;
    long start = (long) (page - 1) * size;
    long end = start + size;

    Set<String> postIds = redisTemplate.opsForZSet().reverseRange(feedKey, start, end);

    if (CollectionUtils.isEmpty(postIds)) {
      return FeedResponseDto.builder()
          .posts(List.of())
          .page(page)
          .size(size)
          .hasMore(false)
          .build();
    }

    List<FeedPostDataDto> posts = loadPostsFromCache(postIds);

    boolean hasMore = posts.size() > size;
    if (hasMore) {
      posts = posts.subList(0, size);
    }

    return FeedResponseDto.builder().posts(posts).page(page).size(size).hasMore(hasMore).build();
  }

  public void trackInteraction(
      Integer userId, Integer postId, Integer authorId, InteractionType type) {
    UserInteractionEntity entity = new UserInteractionEntity();
    entity.setUserId(userId);
    entity.setPostId(postId);
    entity.setAuthorId(authorId);
    entity.setType(type);
    userInteractionRepository.save(entity);
  }

  private List<FeedPostDataDto> loadPostsFromCache(Collection<String> postIds) {
    List<String> keys = postIds.stream().map(id -> POST_CACHE_KEY_PREFIX + id).toList();
    List<String> values = redisTemplate.opsForValue().multiGet(keys);

    if (Objects.isNull(values)) {
      return List.of();
    }

    List<FeedPostDataDto> posts = new ArrayList<>(values.size());
    for (String json : values) {
      try {
        posts.add(objectMapper.readValue(json, FeedPostDataDto.class));
      } catch (Exception e) {
        log.warn("Failed to deserialize cached post", e);
      }
    }
    return posts;
  }

  private void cachePostData(String postId, FeedPostDataDto data) {
    try {
      String json = objectMapper.writeValueAsString(data);
      redisTemplate.opsForValue().set(POST_CACHE_KEY_PREFIX + postId, json, POST_CACHE_TTL);
    } catch (Exception e) {
      log.error("Failed to cache post data for {}", postId, e);
    }
  }

  private void addToFeed(Integer userId, String postId, double score) {
    String feedKey = FEED_KEY_PREFIX + userId;
    redisTemplate.opsForZSet().add(feedKey, postId, score);
    redisTemplate.opsForZSet().removeRange(feedKey, 0, -(MAX_FEED_SIZE + 1));
  }

  private void removeFromFeed(Integer userId, String postId) {
    redisTemplate.opsForZSet().remove(FEED_KEY_PREFIX + userId, postId);
  }
}
