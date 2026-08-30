package com.socialapp.friendships.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Nạp đồ thị bạn bè của bộ seed vào Neo4j lúc khởi động, mỗi khi cờ được bật.
 *
 * <p><b>Vấn đề nó giải quyết, và vì sao nó nghiêm trọng hơn vẻ ngoài.</b> Quan hệ bạn bè sống ở hai
 * nơi: Neo4j giữ cạnh {@code FRIENDS_WITH} và là thứ {@code /friendships} cùng {@code /suggestions}
 * THỰC SỰ đọc, còn Postgres chỉ giữ nhật ký lời mời. Flyway chỉ quản Postgres. Nghĩa là sau khi nạp
 * seed, Postgres có hàng nghìn lời mời ĐÃ CHẤP NHẬN trong khi Neo4j trống trơn — và <b>không có gì
 * báo lỗi</b>: hồ sơ hiện "đã là bạn" còn danh sách bạn bè thì rỗng.
 *
 * <p><b>Vì sao chuyển vào ứng dụng thay vì để ở docker-compose.</b> Máy dev từng có một service
 * {@code neo4j-seed} nạp file cypher qua bind mount. Production chạy compose của repo DATN-infra,
 * nơi không có service đó — nên production nạp seed xong là có một đồ thị bạn bè rỗng, và lỗi ấy
 * chỉ lộ ra khi có người mở danh sách bạn bè. Một cơ chế nằm trong ứng dụng đi theo ứng dụng tới
 * mọi môi trường; một service trong compose thì chỉ có ở nơi người ta nhớ chép nó sang.
 *
 * <p>File cypher vì vậy nằm trong {@code src/main/resources/db/seed} — cùng chỗ với các file SQL
 * mà nó phải khớp, và được đóng vào jar nên không cần mount gì.
 *
 * <p><b>Mặc định TẮT</b>, cùng lý do với {@code NewsfeedSeedInitializer}: chỉ bật ở môi trường
 * thực sự muốn nạp seed. Cờ đó là điều kiện DUY NHẤT.
 *
 * <p><b>Vì sao KHÔNG còn điều kiện "chỉ nạp khi đồ thị còn rỗng".</b> Điều kiện ấy nuốt mất bước
 * dọn của chính file cypher. {@code friend-graph.cypher} mở đầu bằng {@code MATCH (u:User) WHERE
 * u.userId >= 9001 AND u.userId <= 9599 DETACH DELETE u}, viết ra đúng để nạp đè một thế hệ seed
 * cũ — nhưng câu đó nằm TRONG file, còn điều kiện thì chặn TRƯỚC KHI file được đọc. Trên một máy
 * dev đã từng nạp seed, bộ seed mới vì thế nạp xong mà đồ thị vẫn là của thế hệ trước, và hỏng
 * đúng cái kiểu mà lớp này sinh ra để chặn: Postgres nói "đã là bạn", {@code /friendships} trả
 * danh sách cũ, không có gì báo lỗi.
 *
 * <p>Đó không phải trường hợp hiếm mà là mặc định: cả bốn kho dữ liệu trong {@code
 * docker-compose.yml} đều là bind mount dưới {@code ./.docker-data/}, nên {@code docker compose
 * down -v} — vốn chỉ dọn named volume — không xoá Neo4j. Postgres thoát được là nhờ có
 * {@code V80__seed_reset.sql} tự dọn, chứ không phải nhờ {@code down -v}.
 *
 * <p><b>Bỏ điều kiện đi vẫn an toàn cho mọi lần khởi động sau</b>, vì file chạy lại được: sau câu
 * DETACH DELETE có giới hạn dải, toàn bộ phần còn lại là {@code MERGE}. Cái giá là ~500 node và
 * vài nghìn cạnh mỗi lần khởi động CÓ BẬT CỜ — không phải mỗi lần khởi động.
 *
 * <p><b>Không bao giờ làm hỏng lần khởi động.</b> Mọi lỗi được ghi log rồi bỏ qua.
 *
 * <p><b>{@code @Order} ở đây là bắt buộc, không phải trang trí.</b> {@code NewsfeedSeedInitializer}
 * cũng nghe {@code ApplicationReadyEvent}, và việc dựng lại bảng tin gọi
 * {@code friendshipService.getFriendIds()} — tức là ĐỌC CHÍNH ĐỒ THỊ NÀY. Chạy sai thứ tự thì
 * {@code getFriendIds} trả rỗng cho mọi người, bài chỉ fan-out tới người được gắn thẻ, và bảng tin
 * gần như trống — trong khi log vẫn báo {@code processed=2586} y như một lần chạy thành công.
 * Spring không bảo đảm thứ tự giữa các listener không đánh số.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Neo4jSeedInitializer {

  private static final String CYPHER_PATH = "db/seed/friend-graph.cypher";

  private final Driver driver;

  /**
   * Bật cùng lúc với việc nạp seed:
   *
   * <pre>
   * NEO4J_SEED_ON_START=true FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/seed ./gradlew bootRun
   * </pre>
   */
  @Value("${neo4j.seed-on-start:false}")
  private boolean seedOnStart;

  // Order đặt trên PHƯƠNG THỨC chứ không phải trên lớp: ApplicationListenerMethodAdapter phân
  // giải thứ tự từ chính phương thức nghe sự kiện, nên @Order ở cấp lớp có thể bị bỏ qua.
  @Order(10)
  @EventListener(ApplicationReadyEvent.class)
  public void seedFriendGraph() {
    if (!seedOnStart) {
      return;
    }

    try (Session session = driver.session()) {
      List<String> statements = readStatements();
      log.info("neo4j.seed-on-start: đang nạp {} câu lệnh cypher", statements.size());
      for (String statement : statements) {
        session.run(statement);
      }

      long users = session.run("MATCH (u:User) RETURN count(u) AS t").single().get("t").asLong();
      long edges =
          session
              .run("MATCH (:User)-[r:FRIENDS_WITH]->(:User) RETURN count(r) AS t")
              .single()
              .get("t")
              .asLong();
      // Cạnh là hai chiều nên số quan hệ bằng đúng hai lần số cặp bạn bè trong Postgres.
      log.info("neo4j.seed-on-start: xong, {} node User, {} quan hệ FRIENDS_WITH", users, edges);
    } catch (IOException | RuntimeException e) {
      log.error(
          "neo4j.seed-on-start: nạp đồ thị bạn bè thất bại, ứng dụng vẫn khởi động."
              + " Danh sách bạn bè sẽ rỗng cho tới khi nạp lại.",
          e);
    }
  }

  /**
   * Tách file cypher thành từng câu lệnh.
   *
   * <p>Driver của Neo4j chỉ chạy MỘT câu lệnh mỗi lần {@code run()}, khác với {@code cypher-shell}
   * vốn tự tách hộ. Bỏ dòng chú thích trước khi tách: một dấu chấm phẩy nằm trong chú thích sẽ cắt
   * nhầm câu lệnh, và lỗi cú pháp sinh ra từ đó không hề chỉ về phía chú thích.
   */
  private List<String> readStatements() throws IOException {
    String content =
        new String(
            new ClassPathResource(CYPHER_PATH).getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);

    String withoutComments =
        content
            .lines()
            .filter(line -> !line.stripLeading().startsWith("//"))
            .reduce("", (a, b) -> a + "\n" + b);

    return Arrays.stream(withoutComments.split(";"))
        .map(String::strip)
        .filter(statement -> !statement.isEmpty())
        .toList();
  }
}
