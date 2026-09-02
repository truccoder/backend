package com.socialapp.newsfeed.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.socialapp.newsfeed.dto.FeedRebuildResultDto;

/**
 * Component tests for {@link NewsfeedSeedInitializer}. {@link NewsfeedService} and Redis are mocked
 * — no server is touched and {@code rebuildAll()} is never actually run.
 */
@ExtendWith(MockitoExtension.class)
class NewsfeedSeedInitializerTest {

  @Mock private NewsfeedService newsfeedService;
  @Mock private StringRedisTemplate redisTemplate;

  private NewsfeedSeedInitializer initializer;

  @BeforeEach
  void setUp() {
    initializer = new NewsfeedSeedInitializer(newsfeedService, redisTemplate);
    ReflectionTestUtils.setField(initializer, "rebuildOnStart", false);
    ReflectionTestUtils.setField(initializer, "rebuildIfEmpty", false);
  }

  /**
   * A Redis {@code SCAN} cursor over exactly {@code keys}. Built into a local before it reaches any
   * {@code when(...).thenReturn(...)} — Mockito rejects a stub whose argument is itself a stub in
   * progress.
   */
  @SuppressWarnings("unchecked")
  private static Cursor<String> cursorOf(String... keys) {
    Cursor<String> cursor = mock(Cursor.class);
    if (keys.length == 0) {
      when(cursor.hasNext()).thenReturn(false);
      return cursor;
    }
    Boolean[] hasNext = new Boolean[keys.length + 1];
    Arrays.fill(hasNext, Boolean.TRUE);
    hasNext[keys.length] = Boolean.FALSE;
    when(cursor.hasNext()).thenReturn(hasNext[0], Arrays.copyOfRange(hasNext, 1, hasNext.length));
    when(cursor.next()).thenReturn(keys[0], Arrays.copyOfRange(keys, 1, keys.length));
    return cursor;
  }

  @Test
  @DisplayName("cả hai cờ tắt: không đụng gì")
  void bothFlagsOff_doesNothing() {
    initializer.onApplicationReady();

    verifyNoInteractions(newsfeedService);
    verifyNoInteractions(redisTemplate);
  }

  @Test
  @DisplayName("rebuild-if-empty + feed:* rỗng: dựng lại một lần")
  void rebuildIfEmpty_feedEmpty_rebuilds() {
    Cursor<String> before = cursorOf();
    Cursor<String> after = cursorOf("feed:9001", "feed:9002");
    when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(before, after);
    when(newsfeedService.rebuildAll()).thenReturn(new FeedRebuildResultDto(2586, 0));

    initializer.rebuildIfFeedIndexEmpty();

    verify(newsfeedService).rebuildAll();
  }

  @Test
  @DisplayName("rebuild-if-empty + feed:* đã có khoá: không dựng lại")
  void rebuildIfEmpty_feedNotEmpty_skips() {
    Cursor<String> populated = cursorOf("feed:9001");
    when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(populated);

    initializer.rebuildIfFeedIndexEmpty();

    verify(newsfeedService, never()).rebuildAll();
  }

  @Test
  @DisplayName("rebuild-if-empty không bao giờ xoá khoá — chỉ dựng lại")
  void rebuildIfEmpty_neverPurges() {
    Cursor<String> before = cursorOf();
    Cursor<String> after = cursorOf();
    when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(before, after);
    when(newsfeedService.rebuildAll()).thenReturn(new FeedRebuildResultDto(0, 0));

    initializer.rebuildIfFeedIndexEmpty();

    verify(redisTemplate, never()).delete(anyList());
  }

  @Test
  @DisplayName("rebuild-on-start: xoá feed cũ rồi dựng lại")
  void rebuildOnStart_purgesThenRebuilds() {
    Cursor<String> toPurge = cursorOf("feed:old1", "feed:old2");
    Cursor<String> afterRebuild = cursorOf("feed:9001");
    when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(toPurge, afterRebuild);
    when(redisTemplate.delete(anyList())).thenReturn(2L);
    when(newsfeedService.rebuildAll()).thenReturn(new FeedRebuildResultDto(10, 0));

    initializer.rebuildFeeds();

    verify(redisTemplate).delete(List.of("feed:old1", "feed:old2"));
    verify(newsfeedService).rebuildAll();
  }

  @Test
  @DisplayName("onApplicationReady với rebuild-on-start bật: chạy đường dọn-rồi-dựng đồng bộ")
  void onApplicationReady_rebuildOnStart_runsSynchronousPath() {
    ReflectionTestUtils.setField(initializer, "rebuildOnStart", true);
    Cursor<String> purgeScan = cursorOf();
    Cursor<String> countScan = cursorOf();
    when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(purgeScan, countScan);
    when(newsfeedService.rebuildAll()).thenReturn(new FeedRebuildResultDto(5, 0));

    initializer.onApplicationReady();

    verify(newsfeedService).rebuildAll();
  }
}
