package com.socialapp.friendships.cache;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.socialapp.common.cache.CacheTemplate;
import com.socialapp.common.cache.CacheTemplateFactory;
import com.socialapp.friendships.dto.UserProfileDto;

@Component
public class UserProfileCache {
  private static final String KEY_PREFIX = "user:profile:";
  private final CacheTemplate<UserProfileDto> template;

  public UserProfileCache(CacheTemplateFactory factory) {
    this.template = factory.forType(UserProfileDto.class);
  }

  public Optional<UserProfileDto> get(Integer userId) {
    return template.get(key(userId));
  }

  public void set(Integer userId, UserProfileDto profile) {
    template.set(key(userId), profile);
  }

  public UserProfileDto getOrLoad(Integer userId, Supplier<UserProfileDto> loader) {
    return template.getOrLoad(key(userId), loader);
  }

  public void evict(Integer userId) {
    template.evict(key(userId));
  }

  public Map<Integer, UserProfileDto> getOrLoadAll(
      Set<Integer> userIds, Function<Set<Integer>, Map<Integer, UserProfileDto>> batchLoader) {

    Map<Integer, String> idToKey =
        userIds.stream().collect(Collectors.toMap(Function.identity(), this::key));
    Map<String, Integer> keyToId =
        idToKey.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

    Map<String, UserProfileDto> byKey =
        template.getOrLoadAll(
            idToKey.values(),
            (Collection<String> missingKeys) -> {
              Set<Integer> missingIds =
                  missingKeys.stream().map(keyToId::get).collect(Collectors.toSet());

              return batchLoader.apply(missingIds).entrySet().stream()
                  .collect(Collectors.toMap(e -> key(e.getKey()), Map.Entry::getValue));
            });

    return byKey.entrySet().stream()
        .collect(Collectors.toMap(e -> keyToId.get(e.getKey()), Map.Entry::getValue));
  }

  private String key(Integer userId) {
    return KEY_PREFIX + userId;
  }
}
