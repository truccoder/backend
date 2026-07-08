package com.socialapp.friendships.cache;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.socialapp.common.cache.CacheTemplate;
import com.socialapp.common.cache.CacheTemplateFactory;
import com.socialapp.friendships.dto.MutualFriendCountDto;

@Component
public class FriendSuggestionCache {
  private static final String KEY_PREFIX = "friend:suggestions:";
  private final CacheTemplate<List<MutualFriendCountDto>> template;

  public FriendSuggestionCache(CacheTemplateFactory factory) {
    this.template = factory.forListOf(MutualFriendCountDto.class);
  }

  public List<MutualFriendCountDto> getOrLoad(
      Integer userId, Supplier<List<MutualFriendCountDto>> loader) {
    return template.getOrLoad(key(userId), loader);
  }

  public void evict(Integer userId) {
    template.evict(key(userId));
  }

  private String key(Integer userId) {
    return KEY_PREFIX + userId;
  }
}
