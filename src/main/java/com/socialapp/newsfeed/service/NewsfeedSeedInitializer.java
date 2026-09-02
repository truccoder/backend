package com.socialapp.newsfeed.service;

import static com.socialapp.newsfeed.service.PostScoringService.FEED_KEY_PREFIX;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.socialapp.newsfeed.dto.FeedRebuildResultDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dựng lại bảng tin lúc khởi động, mỗi khi cờ được bật.
 *
 * <p><b>Vấn đề nó giải quyết.</b> Bảng tin đọc <em>duy nhất</em> từ Redis và không bao giờ đọc bù
 * từ Postgres, nên sau mỗi lần nạp seed phải gọi {@code POST /v1/api/admin/newsfeed/rebuild} bằng
 * tay — một bước cần token quản trị, tức là cần ứng dụng đã chạy, nên không đưa vào
 * {@code docker compose up} được. Quên nó thì {@code /feed} trống trong khi {@code /posts/public}
 * đầy, và triệu chứng đó trông y hệt một lỗi frontend.
 *
 * <p><b>Vì sao {@code newsfeed.rebuild-on-start} mặc định TẮT.</b> Fan-out toàn bộ bài đã duyệt cho
 * toàn bộ người dùng là công việc nặng và tuyến tính theo số bài. Chạy nó ở MỖI lần khởi động
 * production là trả một cái giá lớn cho một thứ production hầu như không cần. Cờ này (kèm bước dọn
 * bên dưới) là đường của máy dev.
 *
 * <p><b>Vì sao {@code newsfeed.rebuild-if-empty} thì production NÊN bật.</b> Đây là lưới an toàn
 * riêng cho production: chỉ khi {@code SCAN feed:*} ra ĐÚNG 0 khoá lúc khởi động thì mới dựng lại
 * một lần, trên luồng nền. Một {@code feed:*} rỗng trên production nghĩa là (a) database vừa nạp
 * seed — bài seed {@code INSERT} thẳng vào Postgres, không đi qua đường đăng bài nên chưa từng
 * fan-out — hoặc (b) mất Redis. Cả hai đều cần đúng một lần dựng lại, và không phải thứ nên bắt
 * người vận hành nhớ gọi tay. Khác {@code rebuild-on-start}: KHÔNG dọn (chẳng có gì để dọn), KHÔNG
 * chạy khi feed đã có bất cứ thứ gì, và chạy nền vì fan-out ~2.600 bài qua pooler có thể mất một
 * hai phút — một lần deploy bình thường chỉ tốn đúng một lượt {@code SCAN}.
 *
 * <p><b>Vì sao KHÔNG còn điều kiện "Redis chưa có feed nào" trên đường {@code rebuild-on-start}.</b>
 * Điều kiện đó khiến bộ seed mới không bao giờ tới được bảng tin trên một máy dev đã từng nạp seed:
 * Redis là bind mount dưới {@code ./.docker-data/redis} nên {@code docker compose down -v} không xoá
 * nó, feed của thế hệ trước vẫn còn, và lượt {@code SCAN} đầu tiên thấy có khoá rồi bỏ qua. Kết quả
 * là {@code /feed} trả về id của những bài mà {@code V80__seed_reset.sql} vừa xoá — hỏng im lặng, y
 * như trường hợp Neo4j ở {@code Neo4jSeedInitializer}. ({@code rebuild-if-empty} không dính bẫy này
 * vì nó chỉ chạy khi feed rỗng hẳn.)
 *
 * <p><b>Phải XOÁ feed cũ trước, không chỉ dựng lại đè lên.</b> {@code rebuildAll()} chỉ {@code
 * ZADD} thêm; nó không biết gì về những thành viên đã có sẵn trong zset. Với bài của bộ seed hiện
 * tại thì {@code ZADD} chỉ cập nhật điểm nên vô hại, nhưng id của thế hệ trước thì không ai gỡ ra
 * — chúng ở lại vĩnh viễn, chiếm chỗ của bài thật. Nên bước dọn nằm ở đây chứ không nằm trong
 * {@code rebuildAll()}: endpoint quản trị {@code POST /admin/newsfeed/rebuild} là đường phục hồi
 * khi mất Redis ở production, và một lệnh xoá sạch nằm bên trong nó là thứ khác hẳn.
 *
 * <p><b>Không bao giờ làm hỏng lần khởi động.</b> Mọi lỗi ở đây được ghi log rồi bỏ qua — bảng tin
 * trống là bất tiện, còn một ứng dụng không lên được vì Redis chậm thì tệ hơn nhiều. Cùng lý do
 * với {@code MinIOBucketInitializer}.
 *
 * <p><b>{@code @Order(20)} phải LỚN HƠN của {@code Neo4jSeedInitializer}.</b> Fan-out gọi
 * {@code friendshipService.getFriendIds()}, đọc đồ thị bạn bè trong Neo4j. Chạy trước lúc đồ thị
 * được nạp thì mọi người đều không có bạn, bài chỉ tới được người được gắn thẻ, và bảng tin gần như
 * trống — nhưng log vẫn báo {@code processed=2586} nên trông hệt một lần chạy thành công. Cảnh báo
 * "số bảng tin" ở cuối phương thức là thứ duy nhất phân biệt được hai trường hợp đó.
 *
 * <p>Đặt cạnh {@link PostScoringService} thay vì trong một gói {@code config} riêng: nó đọc
 * {@code FEED_KEY_PREFIX} vốn là package-private, và mở rộng phạm vi của một hằng số chỉ để chiều
 * cách sắp gói là đánh đổi sai.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsfeedSeedInitializer {

  private final NewsfeedService newsfeedService;
  private final StringRedisTemplate redisTemplate;

  /**
   * Đường của máy dev: xoá sạch {@code feed:*} rồi fan-out lại toàn bộ, mỗi lần khởi động.
   *
   * <pre>
   * NEWSFEED_REBUILD_ON_START=true ./gradlew bootRun
   * </pre>
   */
  @Value("${newsfeed.rebuild-on-start:false}")
  private boolean rebuildOnStart;

  /**
   * Đường của production: chỉ dựng lại (nền, không dọn) khi {@code feed:*} rỗng hẳn lúc khởi động.
   * {@code application-prod.yml} đặt {@code true}. Xem javadoc của lớp.
   */
  @Value("${newsfeed.rebuild-if-empty:false}")
  private boolean rebuildIfEmpty;

  // Order đặt trên PHƯƠNG THỨC chứ không phải trên lớp — xem chú thích cùng chỗ ở
  // Neo4jSeedInitializer. Số này phải LỚN HƠN số của nó, nên đồ thị bạn bè trong Neo4j đã được nạp
  // (đồng bộ, ở @Order(10)) trước khi fan-out đọc tới nó — kể cả khi bước dựng lại chạy nền.
  @Order(20)
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    if (rebuildOnStart) {
      rebuildFeeds();
      return;
    }
    if (rebuildIfEmpty) {
      Thread worker = new Thread(this::rebuildIfFeedIndexEmpty, "newsfeed-rebuild-if-empty");
      worker.setDaemon(true);
      worker.start();
    }
  }

  /** Đường {@code rebuild-on-start}: dọn rồi dựng lại. Package-private để test gọi trực tiếp. */
  void rebuildFeeds() {
    try {
      long purged = purgeFeeds();
      log.info("newsfeed.rebuild-on-start: đã xoá {} bảng tin cũ, đang dựng lại", purged);
      FeedRebuildResultDto result = newsfeedService.rebuildAll();

      // ĐẾM SỐ BẢNG TIN THỰC SỰ ĐƯỢC TẠO, không chỉ báo processed. Fan-out đọc đồ thị bạn bè từ
      // Neo4j; nếu đồ thị rỗng thì mỗi bài chỉ tới được người được gắn thẻ, và processed vẫn đếm
      // đủ 2.586 bài y như một lần chạy thành công. Con số thứ hai là thứ phân biệt hai trường
      // hợp đó: khoảng 500 nghĩa là đúng, vài chục nghĩa là đồ thị chưa được nạp lúc chạy tới đây.
      long feeds = countFeeds();
      log.info(
          "newsfeed.rebuild-on-start: xong, processed={}, skipped={}, số bảng tin={}",
          result.processed(),
          result.skipped(),
          feeds);
      if (result.processed() > 0 && feeds < 100) {
        log.warn(
            "newsfeed.rebuild-on-start: chỉ {} bảng tin cho {} bài — gần như chắc chắn đồ thị bạn"
                + " bè trong Neo4j còn rỗng lúc dựng lại. Bật NEO4J_SEED_ON_START rồi khởi động"
                + " lại; bước dọn ở đầu phương thức này lo phần Redis.",
            feeds,
            result.processed());
      }
    } catch (RuntimeException e) {
      log.error(
          "newsfeed.rebuild-on-start: dựng lại bảng tin thất bại, ứng dụng vẫn khởi động."
              + " Gọi POST /v1/api/admin/newsfeed/rebuild bằng tay nếu /feed trống.",
          e);
    }
  }

  /**
   * Đường {@code rebuild-if-empty}: dựng lại đúng một lần khi {@code feed:*} rỗng hẳn. Chạy trên
   * luồng nền (xem {@link #onApplicationReady}); package-private để test gọi trực tiếp.
   *
   * <p>Không dọn: {@code countFeeds() == 0} nghĩa là không có gì để dọn. Không có cửa sổ đua đáng
   * lo — nếu một người dùng thật đăng bài giữa lượt {@code SCAN} và {@code rebuildAll()}, bài đó đã
   * nằm trong feed và {@code rebuildAll()} chỉ {@code ZADD} lại đúng điểm cũ.
   */
  void rebuildIfFeedIndexEmpty() {
    try {
      long existing = countFeeds();
      if (existing > 0) {
        log.info("newsfeed.rebuild-if-empty: đã có {} bảng tin, không làm gì", existing);
        return;
      }

      log.info("newsfeed.rebuild-if-empty: feed:* rỗng hẳn — dựng lại toàn bộ trên luồng nền");
      FeedRebuildResultDto result = newsfeedService.rebuildAll();

      long feeds = countFeeds();
      log.info(
          "newsfeed.rebuild-if-empty: xong, processed={}, skipped={}, số bảng tin={}",
          result.processed(),
          result.skipped(),
          feeds);
      if (result.processed() > 0 && feeds < 100) {
        log.warn(
            "newsfeed.rebuild-if-empty: chỉ {} bảng tin cho {} bài — gần như chắc chắn đồ thị bạn"
                + " bè trong Neo4j còn rỗng lúc dựng lại. Kiểm neo4j.seed-on-start rồi khởi động"
                + " lại.",
            feeds,
            result.processed());
      }
    } catch (RuntimeException e) {
      log.error(
          "newsfeed.rebuild-if-empty: dựng lại bảng tin thất bại, ứng dụng vẫn khởi động."
              + " Gọi POST /v1/api/admin/newsfeed/rebuild bằng tay nếu /feed trống.",
          e);
    }
  }

  /**
   * Xoá sạch khoá bảng tin trước khi dựng lại. Trả về số khoá đã xoá.
   *
   * <p>Dùng {@code SCAN} chứ không phải {@code KEYS}: Redis chạy một luồng, và {@code KEYS} quét
   * toàn bộ không gian khoá trong khi chặn mọi lệnh khác.
   *
   * <p>GOM HẾT RỒI MỚI XOÁ, không xoá ngay trong lúc quét. {@code SCAN} chỉ bảo đảm trả đủ những
   * khoá tồn tại xuyên suốt lượt quét; sửa không gian khoá giữa chừng là cách bỏ sót vài khoá mà
   * không có gì báo. Số khoá ở đây là một trên mỗi người dùng, nên giữ cả danh sách trong bộ nhớ
   * là chấp nhận được — cùng cỡ với {@code countFeeds()} ngay bên dưới.
   */
  private long purgeFeeds() {
    List<String> keys = new ArrayList<>();
    try (Cursor<String> cursor =
        redisTemplate.scan(
            ScanOptions.scanOptions().match(FEED_KEY_PREFIX + "*").count(500).build())) {
      while (cursor.hasNext()) {
        keys.add(cursor.next());
      }
    }
    if (keys.isEmpty()) {
      return 0;
    }
    Long removed = redisTemplate.delete(keys);
    return removed == null ? 0 : removed;
  }

  /** Số khoá bảng tin đang có. Chỉ gọi sau khi dựng lại, nên chi phí quét cả không gian là chấp nhận. */
  private long countFeeds() {
    long total = 0;
    try (Cursor<String> cursor =
        redisTemplate.scan(
            ScanOptions.scanOptions().match(FEED_KEY_PREFIX + "*").count(500).build())) {
      while (cursor.hasNext()) {
        cursor.next();
        total++;
      }
    }
    return total;
  }
}
