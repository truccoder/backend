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
 * <p><b>Vì sao mặc định TẮT.</b> Fan-out toàn bộ bài đã duyệt cho toàn bộ người dùng là công việc
 * nặng và tuyến tính theo số bài. Chạy nó ở mỗi lần khởi động production là trả một cái giá lớn để
 * mua một thứ production không cần: ở đó Redis không rỗng, và nếu có rỗng thì đó là sự cố cần
 * người nhìn vào, không phải thứ nên tự vá lúc khởi động.
 *
 * <p><b>Vì sao KHÔNG còn điều kiện "Redis chưa có feed nào".</b> Điều kiện đó khiến bộ seed mới
 * không bao giờ tới được bảng tin trên một máy dev đã từng nạp seed: Redis là bind mount dưới
 * {@code ./.docker-data/redis} nên {@code docker compose down -v} không xoá nó, feed của thế hệ
 * trước vẫn còn, và lượt {@code SCAN} đầu tiên thấy có khoá rồi bỏ qua. Kết quả là {@code /feed}
 * trả về id của những bài mà {@code V80__seed_reset.sql} vừa xoá — hỏng im lặng, y như trường hợp
 * Neo4j ở {@code Neo4jSeedInitializer}. Cờ bây giờ là điều kiện duy nhất, và nó vốn đã mặc định
 * tắt.
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
   * Bật ở máy dev sau khi nạp seed:
   *
   * <pre>
   * NEWSFEED_REBUILD_ON_START=true ./gradlew bootRun
   * </pre>
   */
  @Value("${newsfeed.rebuild-on-start:false}")
  private boolean rebuildOnStart;

  // Order đặt trên PHƯƠNG THỨC chứ không phải trên lớp — xem chú thích cùng chỗ ở
  // Neo4jSeedInitializer. Số này phải LỚN HƠN số của nó.
  @Order(20)
  @EventListener(ApplicationReadyEvent.class)
  public void rebuildFeeds() {
    if (!rebuildOnStart) {
      return;
    }

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
