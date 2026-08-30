package com.socialapp.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Chạy toàn bộ bộ seed thật, trên một PostgreSQL dùng một lần.
 *
 * <p>Không có gì khác làm việc này. {@code application.yml} để {@code spring.flyway.locations}
 * mặc định là {@code classpath:db/migration} và profile test không ghi đè — nên mọi test tích hợp
 * khác chạy trên một database chỉ có schema, và vẫn xanh với một file seed không parse được. Bộ
 * seed chỉ thực sự chạy khi một lập trình viên tự đặt {@code FLYWAY_LOCATIONS}, hoặc trên
 * production. Đúng hai chỗ tệ nhất để phát hiện lỗi cú pháp.
 *
 * <p><b>Container riêng, có chủ đích</b>, thay vì dùng chung với {@code AbstractIntegrationTest}:
 * nạp 500 tài khoản và 2.600 bài vào database mà mọi test khác chia sẻ sẽ đổi thứ những test đó
 * nhìn thấy.
 *
 * <p><b>Giá phải trả và vì sao chấp nhận.</b> Lớp này cộng khoảng 25 giây vào mỗi lần
 * {@code ./gradlew build}. Đổi lấy việc không phát hiện một file seed hỏng ở production là đánh
 * đổi đúng. Ai chạy build lần đầu sẽ thấy nó "đứng" một lúc ở đúng một test — không phải treo.
 *
 * <p><b>Cách viết assertion ở đây.</b> Thế hệ test trước ghi cứng id (5301, 5310, 3021…) nên vỡ
 * ngay lần đổi dữ liệu đầu tiên, và có cả một ca đếm chính xác {@code isEqualTo(11)} — thứ chắc
 * chắn sai với bất kỳ bộ seed nào khác. Ở đây:
 *
 * <ul>
 *   <li>Bài fixture tìm bằng <b>hashtag đánh dấu</b> ({@code fixture_*}, xem V92), không bằng id.
 *   <li>Người dùng tìm bằng <b>id mốc đã cố định trong thiết kế</b> (9001 cao thủ, 9002 người mới,
 *       9499/9500 ADMIN) — đây là hợp đồng với kịch bản demo, không phải chi tiết ngẫu nhiên.
 *   <li>Số lượng assert theo <b>ngưỡng</b> ({@code >= 1200}, {@code > 0}), không assert lại con số
 *       mà generator vừa sinh ra — một test như thế chỉ chứng minh rằng phép sao chép đã đúng.
 * </ul>
 */
class SeedMigrationTest {

  private static PostgreSQLContainer<?> postgres;
  private static Connection connection;

  @BeforeAll
  static void migrate() throws Exception {
    postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("socialapp")
            .withUsername("postgres")
            .withPassword("postgres");
    postgres.start();

    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .schemas("socialapp")
        .defaultSchema("socialapp")
        .createSchemas(true)
        .locations("classpath:db/migration", "classpath:db/seed")
        .placeholders(Map.of("minioUrl", "http://localhost:9000"))
        .load()
        .migrate();

    connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (connection != null) {
      connection.close();
    }
    if (postgres != null) {
      postgres.stop();
    }
  }

  private long count(String sql) throws Exception {
    try (Statement st = connection.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /**
   * Id của bài mang dấu fixture đã cho.
   *
   * <p>Đây là thứ thay cho id ghi cứng. V92 gắn một hashtag {@code fixture_<tên_ca>} vào từng bài
   * fixture, nên đổi dải id hay đổi cách rải của generator đều không phải sửa test — còn nếu dấu
   * không gắn được bài nào thì migration đã đổ ở V92 rồi, chứ không âm thầm biến mất ở đây.
   */
  private long postWithMarker(String marker) throws Exception {
    return count(
        "SELECT ph.post_id FROM socialapp.t_post_hashtags ph"
            + " JOIN socialapp.t_hashtags h ON h.id = ph.hashtag_id"
            + " WHERE h.name = '"
            + marker
            + "'");
  }

  private String one(String sql) throws Exception {
    try (Statement st = connection.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    }
  }

  @Test
  @DisplayName("V81 nạp đúng 500 tài khoản, id liền mạch 9001-9500")
  void fiveHundredUsers() throws Exception {
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_users")).isEqualTo(500);
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_users WHERE id BETWEEN 9001 AND 9500"))
        .isEqualTo(500);
    assertThat(count("SELECT MIN(id) FROM socialapp.t_users")).isEqualTo(9001);
    assertThat(count("SELECT MAX(id) FROM socialapp.t_users")).isEqualTo(9500);
  }

  @Test
  @DisplayName("V80 đã dọn sạch mọi dấu vết của hai thế hệ seed trước")
  void previousGenerationsAreGone() throws Exception {
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_users WHERE email LIKE '%@seed.test'"))
        .isZero();
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_users WHERE email LIKE '%@test.com'"))
        .isZero();
    // Bài của hai thế hệ trước nằm ở dải 5001-5190; bộ mới bắt đầu từ 100001. Không còn hàng nào
    // dưới mốc đó nghĩa là V80 đã dọn sạch, kể cả những bài mà cascade phải kéo theo.
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_posts WHERE id < 100000")).isZero();
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_comments WHERE id < 100000")).isZero();
  }

  @Test
  @DisplayName("mọi email dùng TLD .test, thứ không ai đăng ký được")
  void everyEmailIsUnroutable() throws Exception {
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_users WHERE email NOT LIKE '%@elitenexus.test'"))
        .isZero();
  }

  @Test
  @DisplayName("đúng hai ADMIN, và họ là 9499/9500")
  void exactlyTwoAdmins() throws Exception {
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_users WHERE role = 'ADMIN'")).isEqualTo(2);
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_users WHERE role = 'ADMIN' AND id IN (9499, 9500)"))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("hồ sơ nghề nghiệp có cả người có lẫn người không")
  void professionalProfilesAreOptional() throws Exception {
    long profiles = count("SELECT COUNT(*) FROM socialapp.t_user_professional_profiles");
    assertThat(profiles).isGreaterThan(400).isLessThan(498);
  }

  @Test
  @DisplayName("ảnh đại diện và ảnh bìa đều có cả hai nhánh, và URL đã thay ${minioUrl}")
  void imagesHaveBothBranchesAndResolvedUrls() throws Exception {
    assertThat(
            count("SELECT COUNT(*) FROM socialapp.t_users WHERE profile_picture_url IS NOT NULL"))
        .isGreaterThan(0);
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_users WHERE profile_picture_url IS NULL"))
        .isGreaterThan(0);
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_users WHERE cover_image_url IS NOT NULL"))
        .isGreaterThan(0);
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_users WHERE cover_image_url IS NULL"))
        .isGreaterThan(0);
    // Placeholder phải đã được Flyway thay — còn sót ${ nghĩa là nó đi thẳng vào database.
    assertThat(
            count("SELECT COUNT(*) FROM socialapp.t_users WHERE profile_picture_url LIKE '%${%'"))
        .isZero();
    // Phải có ĐỦ segment bucket. MinIO phục vụ object ở <url>/<bucket>/<key>, và MediaService
    // dựng URL đúng như vậy; manifest thì chỉ giữ <key>, vì minio-init chép /objects/avatars/ vào
    // profile-pictures/avatars/. Bỏ khúc bucket đi thì URL vẫn hợp lệ và vẫn hiện trong JSON —
    // chỉ là MinIO trả 403, tức mọi avatar của bộ seed hỏng mà không có gì báo. Ca kiểm này từng
    // assert đúng cái URL thiếu bucket ấy, nên nó xanh trong suốt thời gian lỗi tồn tại.
    assertThat(one("SELECT profile_picture_url FROM socialapp.t_users WHERE id = 9001"))
        .startsWith("http://localhost:9000/profile-pictures/avatars/9001/");
    assertThat(one("SELECT cover_image_url FROM socialapp.t_users WHERE id = 9001"))
        .startsWith("http://localhost:9000/profile-pictures/covers-user/9001/");
  }

  @Test
  @DisplayName("tuỳ chọn thông báo: mỗi user một dòng, email_frequency chỉ INSTANT hoặc NONE")
  void notificationPreferences() throws Exception {
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_notification_preferences")).isEqualTo(500);
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_notification_preferences"
                    + " WHERE email_frequency NOT IN ('INSTANT', 'NONE')"))
        .isZero();
  }

  @Test
  @DisplayName("sequence của t_users đã bị đẩy quá dải id gán tay")
  void userSequenceIsPushedPast() throws Exception {
    assertThat(count("SELECT last_value FROM socialapp.q_users_id")).isGreaterThan(9500);
  }

  @Test
  @DisplayName("V82 nạp nhật ký lời mời với đủ bốn trạng thái")
  void friendRequestsCoverEveryStatus() throws Exception {
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_friend_requests WHERE status = 'ACCEPTED'"))
        .isGreaterThan(3000);
    for (String status : new String[] {"PENDING", "REJECTED", "CANCELLED"}) {
      assertThat(
              count(
                  "SELECT COUNT(*) FROM socialapp.t_friend_requests WHERE status = '"
                      + status
                      + "'"))
          .as("trạng thái %s", status)
          .isGreaterThan(0);
    }
  }

  @Test
  @DisplayName("chỉ số partial của V33 được tôn trọng: mỗi cặp tối đa một PENDING")
  void atMostOnePendingPerUnorderedPair() throws Exception {
    // Chỉ số đánh trên LEAST/GREATEST nên đảo chiều người gửi cũng không lách được. Nếu generator
    // sinh trùng cặp thì migration đã đổ ở trên rồi — câu này bắt trường hợp trùng mà vẫn lọt.
    assertThat(
            count(
                "SELECT COUNT(*) FROM (SELECT LEAST(requester_id, addressee_id) a,"
                    + " GREATEST(requester_id, addressee_id) b FROM socialapp.t_friend_requests"
                    + " WHERE status = 'PENDING' GROUP BY 1, 2 HAVING COUNT(*) > 1) x"))
        .isZero();
  }

  @Test
  @DisplayName("hai tài khoản ADMIN không có quan hệ bạn bè nào")
  void adminsHaveNoFriends() throws Exception {
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_friend_requests"
                    + " WHERE status = 'ACCEPTED' AND (requester_id IN (9499, 9500)"
                    + " OR addressee_id IN (9499, 9500))"))
        .isZero();
  }

  @Test
  @DisplayName("chặn chỉ nhắm vào cặp CHƯA là bạn, và không ai tự chặn mình")
  void blocksNeverOverlapFriendships() throws Exception {
    // Chặn một người đang là bạn là trạng thái mâu thuẫn mà luồng chặn thật không tạo ra được:
    // chặn sẽ gỡ luôn quan hệ bạn bè. Seed mà tạo ra nó thì màn hình chặn nói một đằng, danh
    // sách bạn bè nói một nẻo.
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_user_blocks")).isGreaterThan(0);
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_user_blocks b"
                    + " JOIN socialapp.t_friend_requests f"
                    + "   ON f.status = 'ACCEPTED'"
                    + "  AND LEAST(f.requester_id, f.addressee_id) = LEAST(b.blocker_id, b.blocked_id)"
                    + "  AND GREATEST(f.requester_id, f.addressee_id) = GREATEST(b.blocker_id, b.blocked_id)"))
        .isZero();
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_user_blocks WHERE blocker_id = blocked_id"))
        .isZero();
  }

  @Test
  @DisplayName("V83 nạp 2600 bài, đủ cả 8 giá trị PostType")
  void postsCoverEveryType() throws Exception {
    // Đếm theo DẢI chứ không theo tổng: V92 cố ý thêm hai bài fixture ngoài dải, và một khẳng
    // định về tổng sẽ biến mọi lần thêm fixture về sau thành một test đỏ vô nghĩa.
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_posts WHERE id BETWEEN 100001 AND 102600"))
        .isEqualTo(2600);
    assertThat(count("SELECT COUNT(DISTINCT post_type) FROM socialapp.t_posts")).isEqualTo(8);
  }

  @Test
  @DisplayName("mọi cột jsonb chi tiết đều là JSON hợp lệ mà Postgres đọc được")
  void everyDetailColumnIsRealJson() throws Exception {
    // Postgres đã từ chối câu INSERT nếu literal không parse được, nên việc migrate chạy xong đã
    // là bằng chứng. Câu này đi xa hơn một bước: đọc NGƯỢC ra một khoá cụ thể của từng loại, tức
    // là chứng minh cấu trúc đúng chứ không chỉ cú pháp đúng.
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_posts WHERE event_details->>'eventTitle' IS NOT NULL"))
        .isEqualTo(80);
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_posts WHERE code_snippet_details->>'language' IS NOT NULL"))
        .isEqualTo(160);
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_posts WHERE poll_details->'options' IS NOT NULL"))
        .isEqualTo(80);
    assertThat(
            count("SELECT COUNT(*) FROM socialapp.t_posts WHERE link_details->>'url' IS NOT NULL"))
        .isEqualTo(120);
  }

  @Test
  @DisplayName("snippet phủ đủ tám ngôn ngữ bắt buộc cộng một ngôn ngữ ngoài danh sách")
  void snippetsCoverEveryLanguageBranch() throws Exception {
    for (String lang :
        new String[] {
          "java", "typescript", "python", "sql", "shell", "json", "css", "plaintext", "zig"
        }) {
      assertThat(
              count(
                  "SELECT COUNT(*) FROM socialapp.t_posts"
                      + " WHERE code_snippet_details->>'language' = '"
                      + lang
                      + "'"))
          .as("ngôn ngữ %s", lang)
          .isGreaterThan(0);
    }
  }

  @Test
  @DisplayName("độ dài nội dung có cả bài rất dài lẫn bài vừa, để phân biệt cắt đúng với cắt tất")
  void contentLengthsCoverBothBranches() throws Exception {
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_posts WHERE LENGTH(content) >= 1200"))
        .isGreaterThan(0);
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_posts"
                    + " WHERE LENGTH(content) BETWEEN 550 AND 750"))
        .isGreaterThan(0);
  }

  @Test
  @DisplayName("ảnh bài viết phủ bố cục 1, 2 và từ 4 ảnh, cộng một object cố ý không tồn tại")
  void postImagesCoverEveryLayout() throws Exception {
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_posts WHERE jsonb_array_length(images) = 1"))
        .isGreaterThan(0);
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_posts WHERE jsonb_array_length(images) = 2"))
        .isGreaterThan(0);
    assertThat(
            count("SELECT COUNT(*) FROM socialapp.t_posts WHERE jsonb_array_length(images) >= 4"))
        .isGreaterThan(0);
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_posts WHERE images::text LIKE '%khong-ton-tai%'"))
        .isEqualTo(1);
    // Không còn placeholder nào sót lại sau khi Flyway thay.
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_posts WHERE images::text LIKE '%${%'"))
        .isZero();
  }

  @Test
  @DisplayName("usage_count của hashtag khớp số bài thật sự gắn thẻ")
  void hashtagCountsMatchReality() throws Exception {
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_hashtags WHERE id BETWEEN 1001 AND 1120"))
        .isEqualTo(120);
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_hashtags h"
                    + " WHERE h.usage_count <> (SELECT COUNT(*) FROM socialapp.t_post_hashtags ph"
                    + "                          WHERE ph.hashtag_id = h.id)"))
        .isZero();
  }

  @Test
  @DisplayName("hàng đợi kiểm duyệt có việc thật: đủ ba trạng thái")
  void moderationQueueHasWork() throws Exception {
    for (String status : new String[] {"APPROVED", "PENDING_REVIEW", "REJECTED"}) {
      assertThat(
              count(
                  "SELECT COUNT(*) FROM socialapp.t_posts WHERE moderation_status = '"
                      + status
                      + "'"))
          .as("trạng thái %s", status)
          .isGreaterThan(0);
    }
  }

  @Test
  @DisplayName("V84 · bình luận chỉ nhận LIKE, còn bài viết giữ đủ bảy loại cảm xúc")
  void theTwoReactionRulesStaySeparate() throws Exception {
    // Luật B24. V73 — thứ từng migrate dữ liệu cũ về LIKE — nay là no-op vì nó chạy trước bộ seed
    // này trên bảng rỗng, nên đây là lưới duy nhất còn lại.
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_comment_reactions WHERE reaction_type <> 'LIKE'"))
        .isZero();
    assertThat(count("SELECT COUNT(DISTINCT reaction_type) FROM socialapp.t_post_reactions"))
        .isEqualTo(7);
    for (String type : new String[] {"INSIGHT", "CLAP"}) {
      assertThat(
              count(
                  "SELECT COUNT(*) FROM socialapp.t_post_reactions WHERE reaction_type = '"
                      + type
                      + "'"))
          .as("loại %s", type)
          .isGreaterThan(0);
    }
    // Tài khoản demo phải có một cảm xúc khác LIKE — nếu không, chỉ cần nhìn màn hình demo là
    // tưởng ứng dụng chỉ có một nút thích.
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_post_reactions"
                    + " WHERE user_id = 9001 AND reaction_type <> 'LIKE'"))
        .isGreaterThan(0);
  }

  @Test
  @DisplayName("lượt thích bình luận không đều nhau, để xếp hạng có gì mà xếp")
  void commentLikeCountsAreUneven() throws Exception {
    assertThat(
            count(
                "SELECT COUNT(DISTINCT total) FROM (SELECT comment_id, COUNT(*) total"
                    + " FROM socialapp.t_comment_reactions GROUP BY comment_id) x"))
        .isGreaterThan(3);
  }

  @Test
  @DisplayName("có bài đúng 0, đúng 1, đúng 2 và từ 5 bình luận gốc trở lên")
  void rootCommentCountsCoverEveryBranch() throws Exception {
    String rootCount =
        "SELECT COUNT(*) FROM socialapp.t_comments WHERE parent_id IS NULL AND post_id = ";
    assertThat(count(rootCount + postWithMarker("fixture_zero_comments"))).isZero();
    assertThat(count(rootCount + postWithMarker("fixture_one_comment"))).isEqualTo(1);
    assertThat(count(rootCount + postWithMarker("fixture_two_comments"))).isEqualTo(2);
    assertThat(count(rootCount + postWithMarker("fixture_many_comments")))
        .isGreaterThanOrEqualTo(5);
  }

  @Test
  @DisplayName("một bài cố ý không có cảm xúc nào")
  void onePostHasNoReactions() throws Exception {
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_post_reactions WHERE post_id = "
                    + postWithMarker("fixture_zero_reactions")))
        .isZero();
  }

  @Test
  @DisplayName("mọi trả lời trỏ về bình luận gốc CỦA CHÍNH BÀI ĐÓ")
  void repliesNeverCrossPosts() throws Exception {
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_comments c"
                    + " JOIN socialapp.t_comments parent ON parent.id = c.parent_id"
                    + " WHERE parent.post_id <> c.post_id"))
        .isZero();
  }

  @Test
  @DisplayName("acceptedAnswerId trỏ tới bình luận có thật của chính bài hỏi đáp đó")
  void acceptedAnswerBelongsToItsOwnPost() throws Exception {
    // Trỏ sang bình luận của bài khác thì API vẫn trả về bình thường, chỉ là giao diện hiện một
    // câu trả lời không nằm trong luồng — sai mà không có gì báo.
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_posts p"
                    + " WHERE p.post_type = 'QNA'"
                    + "   AND p.qna_details->>'acceptedAnswerId' IS NOT NULL"))
        .isGreaterThan(0);
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_posts p"
                    + " WHERE p.post_type = 'QNA'"
                    + "   AND p.qna_details->>'acceptedAnswerId' IS NOT NULL"
                    + "   AND NOT EXISTS (SELECT 1 FROM socialapp.t_comments c"
                    + "                    WHERE c.id = (p.qna_details->>'acceptedAnswerId')::int"
                    + "                      AND c.post_id = p.id)"))
        .isZero();
  }

  @Test
  @DisplayName("có bình luận rất dài, và một bình luận mà dấu @ là email chứ không nhắc ai")
  void commentFixtures() throws Exception {
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_comments WHERE LENGTH(content) >= 300"))
        .isGreaterThan(0);
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_comments WHERE content LIKE '%ho.tro@elitenexus.test%'"))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_comments WHERE content LIKE '@%'"))
        .isGreaterThan(0);
  }

  @Test
  @DisplayName("mỗi bài nộp quiz có số đáp án bằng đúng số câu hỏi của quiz")
  void quizAnswerCountMatchesQuestionCount() throws Exception {
    // QuizService từ chối bài nộp lệch số, nên một hàng lệch ở đây là dữ liệu mà API không bao
    // giờ tạo ra được.
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_quiz_answers")).isGreaterThan(0);
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_quiz_answers a"
                    + " JOIN socialapp.t_posts p ON p.id = a.post_id"
                    + " WHERE jsonb_array_length(a.answers)"
                    + "    <> jsonb_array_length(p.quiz_details->'questions')"))
        .isZero();
  }

  @Test
  @DisplayName("RSVP phủ cả ba trạng thái")
  void rsvpsCoverEveryStatus() throws Exception {
    for (String status : new String[] {"GOING", "INTERESTED", "NOT_GOING"}) {
      assertThat(
              count("SELECT COUNT(*) FROM socialapp.t_event_rsvps WHERE status = '" + status + "'"))
          .as("trạng thái %s", status)
          .isGreaterThan(0);
    }
  }

  @Test
  @DisplayName("không ai thả cảm xúc hay bình luận vào chính bài của mình")
  void nobodyEngagesWithTheirOwnPost() throws Exception {
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_post_reactions r"
                    + " JOIN socialapp.t_posts p ON p.id = r.post_id"
                    + " WHERE p.author_id = r.user_id"))
        .isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_comments c"
                    + " JOIN socialapp.t_posts p ON p.id = c.post_id"
                    + " WHERE p.author_id = c.author_id"))
        .isZero();
  }

  @Test
  @DisplayName("V85 · 80 quyển sách thật, avg_rating khớp đánh giá thật")
  void bookstoreAggregatesMatchReality() throws Exception {
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_books")).isEqualTo(80);
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_books b"
                    + " WHERE b.review_count <> (SELECT COUNT(*) FROM socialapp.t_book_reviews r"
                    + "                           WHERE r.book_id = b.id)"))
        .isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_books b"
                    + " JOIN (SELECT book_id, ROUND(AVG(rating)::numeric, 1) a"
                    + "         FROM socialapp.t_book_reviews GROUP BY book_id) r"
                    + "   ON r.book_id = b.id"
                    + " WHERE b.avg_rating <> r.a"))
        .isZero();
  }

  @Test
  @DisplayName("khoá MinIO của sách là KEY TRẦN, không lẫn URL hay placeholder")
  void bookKeysAreBareObjectKeys() throws Exception {
    // BookStorageService tự ký URL tạm thời khi phục vụ, nên một cột *_key chứa http:// là sai
    // kiểu dữ liệu chứ không chỉ thừa — URL ký ra sẽ trỏ vào một key không tồn tại.
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_books"
                    + " WHERE file_key LIKE 'http%' OR cover_image_key LIKE 'http%'"
                    + "    OR preview_file_key LIKE 'http%'"))
        .isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_books"
                    + " WHERE cover_image_key NOT LIKE 'covers/%' OR file_key NOT LIKE 'books/%'"))
        .isZero();
    assertThat(
            count("SELECT COUNT(*) FROM socialapp.t_books WHERE file_key LIKE '%khong-ton-tai%'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("sách trải nhiều chủ đề, và có một chủ đề chỉ đúng một quyển")
  void bookCategoriesAreUnevenOnPurpose() throws Exception {
    // Tab nào cũng ra chừng ấy kết quả thì không chứng minh được bộ lọc có thật sự lọc. Chủ đề
    // chỉ có một quyển là ca kiểm thử phân trang đáng giá nhất: quyển đó không nằm ở trang đầu.
    assertThat(count("SELECT COUNT(DISTINCT category) FROM socialapp.t_books"))
        .isGreaterThanOrEqualTo(3);
    assertThat(
            count(
                "SELECT COUNT(*) FROM (SELECT category FROM socialapp.t_books"
                    + " GROUP BY category HAVING COUNT(*) = 1) x"))
        .isGreaterThan(0);
  }

  @Test
  @DisplayName("giao dịch đủ bốn trạng thái, và PENDING không chặn kịch bản demo")
  void purchaseStatusesAndTheFreshPendingRule() throws Exception {
    for (String status : new String[] {"COMPLETED", "PENDING", "FAILED", "REFUNDED"}) {
      assertThat(
              count(
                  "SELECT COUNT(*) FROM socialapp.t_book_purchases WHERE payment_status = '"
                      + status
                      + "'"))
          .as("trạng thái %s", status)
          .isGreaterThan(0);
    }
    // ĐÚNG MỘT hàng PENDING nằm trong cửa sổ 15 phút của PENDING_PAYMENT_STALE_MINUTES. Nhiều hơn
    // một nghĩa là có quyển khác cũng đang bị khoá không cho mua lại — rất dễ thành sự cố trên
    // sân khấu, vì triệu chứng là nút mua báo "quay lại sau" chứ không phải một lỗi rõ ràng.
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_book_purchases"
                    + " WHERE payment_status = 'PENDING'"
                    + "   AND created_at > now() - INTERVAL '15 minutes'"))
        .isEqualTo(1);
    // payment_method chỉ có MOMO; 'ATM' không còn hợp lệ sau khi MomoApiClient đổi luồng.
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_book_purchases"
                    + " WHERE payment_method IS NOT NULL AND payment_method <> 'MOMO'"))
        .isZero();
    // Giao dịch chưa tới đích thì không được có mã giao dịch của cổng thanh toán.
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_book_purchases"
                    + " WHERE payment_status IN ('PENDING', 'FAILED')"
                    + "   AND (gateway_transaction_no IS NOT NULL OR paid_at IS NOT NULL)"))
        .isZero();
  }

  @Test
  @DisplayName("V86 · một bản giải thích chứa Markdown thật, đủ tám loại phần tử")
  void oneExplanationCoversEveryMarkdownElement() throws Exception {
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_explanations")).isEqualTo(1600);
    for (String needle :
        new String[] {"## ", "**", "* `", "1. ", "```java", "| --- |", "](https://"}) {
      assertThat(
              count(
                  "SELECT COUNT(*) FROM socialapp.t_explanations"
                      + " WHERE explanation_content LIKE '%"
                      + needle.replace("'", "''")
                      + "%'"))
          .as("phần tử Markdown %s", needle)
          .isGreaterThan(0);
    }
    // …và văn xuôi thuần vẫn phải tồn tại bên cạnh: nếu bản nào cũng có Markdown thì nhánh dựng
    // văn xuôi không bao giờ chạy.
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_explanations"
                    + " WHERE explanation_content NOT LIKE '%##%'"))
        .isGreaterThan(0);
  }

  @Test
  @DisplayName("V87 · bốn cột jsonb giao được nhau, nên gợi ý dự án không rỗng")
  void theFourJsonbColumnsShareOneVocabulary() throws Exception {
    // Đây là cái bẫy hỏng-im-lặng nguy hiểm nhất của cả bộ seed: lệch từ vựng thì phép giao của
    // ProfileMatchScorer luôn rỗng, /projects/suggested trả mảng trống, và endpoint vẫn 200.
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_projects p"
                    + " JOIN socialapp.t_user_professional_profiles up"
                    + "   ON up.interested_domains @> p.tags OR p.tags @> up.interested_domains"
                    + " WHERE p.tags IS NOT NULL"))
        .isGreaterThan(0);
    // Cụ thể cho tài khoản demo 9001, vì đó là thứ lên màn hình chiếu.
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_projects p"
                    + " WHERE p.status = 'OPEN' AND p.tags IS NOT NULL"
                    + "   AND EXISTS (SELECT 1 FROM socialapp.t_user_professional_profiles up"
                    + "                WHERE up.user_id = 9001"
                    + "                  AND up.interested_domains @> p.tags)"))
        .isGreaterThan(0);
    // required_skills của vị trí tuyển cũng phải giao được với known_tech_stack của hồ sơ.
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_project_positions pos"
                    + " JOIN socialapp.t_user_professional_profiles up"
                    + "   ON up.known_tech_stack @> (pos.required_skills - 3)"))
        .isGreaterThan(0);
  }

  @Test
  @DisplayName("dự án phủ đủ ba ca tags: giao được, CLOSED có tags, và tags NULL")
  void projectTagFixtures() throws Exception {
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_projects")).isEqualTo(50);
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_projects WHERE status = 'CLOSED' AND tags IS NOT NULL"))
        .isGreaterThan(0);
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_projects WHERE tags IS NULL"))
        .isGreaterThan(0);
  }

  @Test
  @DisplayName("sequence SERIAL của ba bảng dự án đã được đẩy quá id gán tay")
  void serialSequencesArePushedPast() throws Exception {
    // Ba bảng này dùng SERIAL chứ không phải q_*, nên rất dễ bị bỏ sót — và lỗi không nổ lúc
    // migrate mà nổ ở lần tạo dự án đầu tiên của một người dùng thật.
    for (String table :
        new String[] {"t_projects", "t_project_positions", "t_project_applications"}) {
      assertThat(
              count(
                  "SELECT last_value FROM "
                      + one("SELECT pg_get_serial_sequence('socialapp." + table + "', 'id')")))
          .as("sequence của %s", table)
          .isGreaterThanOrEqualTo(count("SELECT COALESCE(MAX(id), 0) FROM socialapp." + table));
    }
  }

  @Test
  @DisplayName("không ai nộp đơn vào dự án của chính mình, và chỉ nộp vào vị trí đang mở")
  void applicationsRespectTheirOwnRules() throws Exception {
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_project_applications a"
                    + " JOIN socialapp.t_projects p ON p.id = a.project_id"
                    + " WHERE p.author_id = a.applicant_id"))
        .isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_project_applications a"
                    + " JOIN socialapp.t_project_positions pos ON pos.id = a.position_id"
                    + " WHERE pos.project_id <> a.project_id"))
        .isZero();
  }

  @Test
  @DisplayName("V90 · elite_score bằng đúng tổng điểm uy tín của từng người")
  void eliteScoreEqualsSumOfReputationPoints() throws Exception {
    // Bất biến dễ lệch nhất của cả bộ seed: mọi thứ khác đều hiện ra trên giao diện, còn một
    // elite_score sai thì trông vẫn hoàn toàn hợp lý.
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_users u"
                    + " WHERE u.elite_score <> COALESCE("
                    + "   (SELECT SUM(points) FROM socialapp.t_reputation_events e"
                    + "     WHERE e.user_id = u.id), 0)"))
        .isZero();
  }

  @Test
  @DisplayName("điểm uy tín dẫn xuất từ hoạt động thật, đúng bảng điểm trong mã nguồn")
  void reputationPointsMatchTheSourceOfTruth() throws Exception {
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_reputation_events"
                    + " WHERE (source_type = 'REACTION_RECEIVED' AND points <> 1)"
                    + "    OR (source_type = 'ACCEPTED_ANSWER' AND points <> 15)"
                    + "    OR (source_type = 'ROADMAP_SELF_VERIFIED' AND points <> 5)"
                    + "    OR (source_type = 'ROADMAP_NODE_VERIFIED' AND points <> 20)"
                    + "    OR (source_type = 'PROJECT_APPLICATION_ACCEPTED' AND points <> 10)"))
        .isZero();
    // Mỗi sự kiện REACTION_RECEIVED phải soi được về một cảm xúc CÓ THẬT — nếu không thì con số
    // trên hồ sơ không tương ứng với bất cứ thứ gì nhìn thấy được trên giao diện.
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_reputation_events e"
                    + " WHERE e.source_type = 'REACTION_RECEIVED'"
                    + "   AND NOT EXISTS (SELECT 1 FROM socialapp.t_post_reactions r"
                    + "                    WHERE r.post_id = split_part(e.source_id, ':', 1)::int"
                    + "                      AND r.user_id = split_part(e.source_id, ':', 2)::int)"))
        .isZero();
  }

  @Test
  @DisplayName("hạng trải rộng, và tài khoản demo 9001 KHÔNG còn là Newcomer")
  void repLevelsAreSpreadAndTheDemoAccountIsNot() throws Exception {
    // Ngưỡng theo RepLevel: NEWCOMER 0, CONTRIBUTOR 100, PRACTITIONER 1000, EXPERT 5000.
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_users WHERE elite_score >= 100"))
        .isGreaterThan(0);
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_users WHERE elite_score < 100"))
        .isGreaterThan(0);
    assertThat(count("SELECT elite_score FROM socialapp.t_users WHERE id = 9001"))
        .isGreaterThanOrEqualTo(100);
  }

  @Test
  @DisplayName("luật post_id của V72 được giữ, và V72 nay là no-op nên đây là lưới duy nhất")
  void notificationPostIdRuleHolds() throws Exception {
    // reference_type = 'COMMENT' → post_id phải khác NULL và bằng đúng post của bình luận đó.
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_notifications n"
                    + " WHERE n.reference_type = 'COMMENT'"
                    + "   AND (n.post_id IS NULL"
                    + "        OR n.post_id <> (SELECT c.post_id FROM socialapp.t_comments c"
                    + "                          WHERE c.id = n.reference_id))"))
        .isZero();
    // Mọi loại khác → post_id phải NULL, vì DTO dùng @JsonInclude(NON_NULL) và một giá trị thừa
    // làm JSON mọc thêm khoá lạ ở FRIEND_REQUEST, BOOK_PURCHASED…
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_notifications"
                    + " WHERE reference_type <> 'COMMENT' AND post_id IS NOT NULL"))
        .isZero();
    // Cả hai loại trỏ-sang-bình-luận đều phải có mặt, không chỉ một.
    for (String type : new String[] {"USER_MENTIONED", "COMMENT_LIKED"}) {
      assertThat(
              count("SELECT COUNT(*) FROM socialapp.t_notifications WHERE type = '" + type + "'"))
          .as("loại %s", type)
          .isGreaterThan(0);
    }
  }

  @Test
  @DisplayName("bình luận có dấu @ là email KHÔNG sinh thông báo nhắc tên")
  void anEmailAddressIsNotAMention() throws Exception {
    long emailComment =
        count(
            "SELECT id FROM socialapp.t_comments"
                + " WHERE content LIKE '%ho.tro@elitenexus.test%' LIMIT 1");
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_notifications"
                    + " WHERE type = 'USER_MENTIONED' AND reference_id = "
                    + emailComment))
        .isZero();
  }

  @Test
  @DisplayName("thông báo có cả đã đọc lẫn chưa đọc")
  void notificationsCoverBothReadStates() throws Exception {
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_notifications WHERE is_read"))
        .isGreaterThan(0);
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_notifications WHERE NOT is_read"))
        .isGreaterThan(0);
  }

  @Test
  @DisplayName("hồ sơ mang đúng bộ từ vựng chủ đề mà matchmaking so giao")
  void profilesCarryTheSharedVocabulary() throws Exception {
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_user_professional_profiles"
                    + " WHERE interested_domains @> '[\"API Design\"]'::jsonb"))
        .isGreaterThan(0);
  }

  @Test
  @DisplayName("V88 · cây nút lộ trình: cha con cùng lộ trình, cha khai trước, đủ cả hai bậc")
  void roadmapNodesFormAParentChildTree() throws Exception {
    // Mỗi nút con phải trỏ vào một nút cha CÙNG lộ trình — một parent lệch roadmap làm màn hình
    // cây lộ trình chèn một nút lạ vào nhánh sai, và không một endpoint nào báo lỗi.
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_roadmap_nodes c"
                    + " JOIN socialapp.t_roadmap_nodes p ON p.id = c.parent_node_id"
                    + " WHERE p.roadmap_id <> c.roadmap_id"))
        .isZero();
    // Và trong seed thật này cây phải có cả gốc lẫn con — một thế hệ seed mà mọi parent_node_id
    // đều NULL thì cây đã bị làm phẳng, tức là mất đúng tính năng này.
    assertThat(count("SELECT COUNT(*) FROM socialapp.t_roadmap_nodes WHERE parent_node_id IS NULL"))
        .isGreaterThan(0);
    assertThat(
            count(
                "SELECT COUNT(*) FROM socialapp.t_roadmap_nodes WHERE parent_node_id IS NOT NULL"))
        .isGreaterThan(0);
  }

  /**
   * Mọi cột ánh xạ bằng {@code @Enumerated(EnumType.STRING)} chỉ được chứa những chuỗi mà enum
   * Java tương ứng đọc lại được.
   *
   * <p><b>Lỗ hổng mà test này bịt.</b> Các cột ấy là {@code varchar} không có {@code CHECK}, nên
   * một nhãn tự chế đi qua Flyway mà không một lời cảnh báo nào, qua cả 40-mấy assertion SQL của
   * lớp này, rồi mới nổ ở Hibernate lúc đọc hàng — nghĩa là ở tầng ứng dụng, sau khi mọi thứ đã
   * xanh. Ngày 28/08 có sáu cột dính cùng lúc: {@code explanation_style} mang {@code 'ANALOGY'}
   * (enum viết {@code ANALOGY_HEAVY}) làm 256 trong 500 tài khoản không đăng nhập được vì
   * {@code TokenService.issueTokens} đọc hồ sơ nghề nghiệp ngay trong luồng đăng nhập;
   * {@code t_notifications.channel} mang {@code 'IN_APP'} ở cả 23.051 hàng làm
   * {@code GET /notifications} trả 500 cho mọi tài khoản. Cả sáu đều là chuỗi nghe rất hợp lý —
   * đó chính là lý do không ai đọc ra khi review.
   *
   * <p><b>Vì sao quét bằng reflection chứ không liệt kê tay.</b> Một danh sách cột gõ tay sẽ đúng
   * đúng một ngày: thêm một enum mới vào entity thì không có gì nhắc người viết quay lại đây, và
   * một test canh chừng đã ngừng canh còn tệ hơn không có test. Quét thì cột mới tự vào tầm ngắm.
   *
   * <p>Test đọc dữ liệu SEED THẬT vừa nạp, nên nó chỉ nói về những giá trị bộ seed thực sự sinh
   * ra — đúng phạm vi cần: nó không thay được ràng buộc ở tầng schema, nó chặn bộ seed ghi ra thứ
   * ứng dụng không đọc nổi.
   */
  @Test
  @DisplayName("mọi cột @Enumerated(STRING) chỉ chứa giá trị mà enum Java đọc lại được")
  void everyEnumeratedColumnParsesBackIntoItsJavaEnum() throws Exception {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

    List<String> problems = new ArrayList<>();
    int columnsChecked = 0;

    for (BeanDefinition candidate : scanner.findCandidateComponents("com.socialapp")) {
      Class<?> entity = Class.forName(candidate.getBeanClassName());
      Table table = entity.getAnnotation(Table.class);
      if (table == null || table.name().isBlank()) {
        continue;
      }

      for (Field field : entity.getDeclaredFields()) {
        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        if (enumerated == null || enumerated.value() != EnumType.STRING) {
          continue;
        }

        String column = columnNameOf(field);
        if (count(
                "SELECT COUNT(*) FROM information_schema.columns"
                    + " WHERE table_schema = 'socialapp' AND table_name = '"
                    + table.name()
                    + "' AND column_name = '"
                    + column
                    + "'")
            == 0) {
          // Không bỏ qua im lặng: một cột không tra được nghĩa là phép suy tên cột của test này
          // đã lệch, và một test tự loại bỏ chính thứ nó phải canh thì vô dụng đúng lúc cần nhất.
          problems.add(
              table.name()
                  + "."
                  + column
                  + " (từ "
                  + entity.getSimpleName()
                  + "."
                  + field.getName()
                  + ") không có trong schema — thêm @Column(name=...) hoặc sửa columnNameOf()");
          continue;
        }

        columnsChecked++;
        Set<String> valid = new HashSet<>();
        for (Object constant : field.getType().getEnumConstants()) {
          valid.add(((Enum<?>) constant).name());
        }

        try (Statement st = connection.createStatement();
            ResultSet rs =
                st.executeQuery(
                    "SELECT \""
                        + column
                        + "\"::text, COUNT(*) FROM socialapp.\""
                        + table.name()
                        + "\" WHERE \""
                        + column
                        + "\" IS NOT NULL GROUP BY 1")) {
          while (rs.next()) {
            String value = rs.getString(1);
            if (!valid.contains(value)) {
              problems.add(
                  table.name()
                      + "."
                      + column
                      + " chứa '"
                      + value
                      + "' ở "
                      + rs.getLong(2)
                      + " hàng — "
                      + field.getType().getSimpleName()
                      + " chỉ có "
                      + valid);
            }
          }
        }
      }
    }

    // Chốt chặn cho chính vòng quét: nếu scanner không tìm được entity nào (đổi tên gói, đổi cách
    // đóng gói test), danh sách problems rỗng và test xanh mà không kiểm gì cả.
    assertThat(columnsChecked)
        .as("số cột @Enumerated(STRING) quét được")
        .isGreaterThanOrEqualTo(20);
    assertThat(problems).isEmpty();
  }

  /**
   * Mọi cột {@code jsonb} ánh xạ bằng {@code @JdbcTypeCode(SqlTypes.JSON)} phải đọc lại được
   * thành đúng kiểu Java mà entity khai.
   *
   * <p><b>Anh em sinh đôi của ca kiểm enum ở trên, khác cơ chế.</b> Cột là {@code jsonb} nên
   * Postgres chỉ đòi JSON hợp lệ — một mảng chuỗi nằm ở chỗ đáng lẽ là mảng đối tượng vẫn chèn
   * được, và mọi assertion SQL vẫn xanh, vì SQL không biết gì về hình dạng bên trong. Hibernate
   * mới là chỗ nổ, lúc đọc hàng. Ngày 28/08: {@code t_explanations.external_links} mang
   * {@code ["https://…"]} trong khi kiểu là {@code List<ExternalLink>} — và vì hàng đó nằm trong
   * Kho lưu trữ của tài khoản demo, cả màn hình trả 500 với
   * {@code Could not deserialize string to java type}.
   *
   * <p>Bẫy cụ thể ở dự án này: {@code t_vault_notes.links} ĐÚNG là {@code List<String>} còn
   * {@code t_explanations.external_links} là {@code List<ExternalLink>}. Hai cột cùng tên gọi
   * "links", hai kiểu khác nhau — sao chép hình dạng từ bên này sang bên kia là đủ để hỏng.
   *
   * <p>Test đọc <b>mọi hàng</b> chứ không lấy mẫu: chi phí không đáng kể ở quy mô này, và một cột
   * chỉ sai ở vài hàng là đúng cái ca mà lấy mẫu sẽ bỏ sót.
   * {@code FAIL_ON_UNKNOWN_PROPERTIES} để tắt, cùng tinh thần với Hibernate — mục tiêu là bắt
   * lệch KIỂU, không phải bắt một khoá thừa vô hại.
   */
  @Test
  @DisplayName("mọi cột jsonb đọc lại được thành đúng kiểu Java mà entity khai")
  void everyJsonColumnDeserialisesIntoItsJavaType() throws Exception {
    ObjectMapper mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

    List<String> problems = new ArrayList<>();
    int columnsChecked = 0;

    for (BeanDefinition candidate : scanner.findCandidateComponents("com.socialapp")) {
      Class<?> entity = Class.forName(candidate.getBeanClassName());
      Table table = entity.getAnnotation(Table.class);
      if (table == null || table.name().isBlank()) {
        continue;
      }

      for (Field field : entity.getDeclaredFields()) {
        JdbcTypeCode jdbcType = field.getAnnotation(JdbcTypeCode.class);
        if (jdbcType == null || jdbcType.value() != SqlTypes.JSON) {
          continue;
        }

        String column = columnNameOf(field);
        if (count(
                "SELECT COUNT(*) FROM information_schema.columns"
                    + " WHERE table_schema = 'socialapp' AND table_name = '"
                    + table.name()
                    + "' AND column_name = '"
                    + column
                    + "'")
            == 0) {
          problems.add(
              table.name()
                  + "."
                  + column
                  + " (từ "
                  + entity.getSimpleName()
                  + "."
                  + field.getName()
                  + ") không có trong schema — thêm @Column(name=...) hoặc sửa columnNameOf()");
          continue;
        }

        columnsChecked++;
        JavaType target = mapper.getTypeFactory().constructType(field.getGenericType());
        int failures = 0;
        String firstFailure = null;

        try (Statement st = connection.createStatement();
            ResultSet rs =
                st.executeQuery(
                    "SELECT \""
                        + column
                        + "\"::text FROM socialapp.\""
                        + table.name()
                        + "\" WHERE \""
                        + column
                        + "\" IS NOT NULL")) {
          while (rs.next()) {
            String json = rs.getString(1);
            try {
              mapper.readValue(json, target);
            } catch (Exception e) {
              failures++;
              if (firstFailure == null) {
                firstFailure =
                    json.substring(0, Math.min(160, json.length())) + " → " + e.getMessage();
              }
            }
          }
        }

        if (failures > 0) {
          problems.add(
              table.name()
                  + "."
                  + column
                  + ": "
                  + failures
                  + " hàng không đọc được thành "
                  + target
                  + " — ví dụ "
                  + firstFailure);
        }
      }
    }

    assertThat(columnsChecked).as("số cột jsonb quét được").isGreaterThanOrEqualTo(10);
    assertThat(problems).isEmpty();
  }

  /**
   * Tên cột của một trường, theo đúng thứ tự Hibernate quyết định: {@code @Column(name=...)} nếu
   * có, không thì chiến lược đặt tên mặc định của Spring Boot — camelCase tách bằng gạch dưới.
   */
  private String columnNameOf(Field field) {
    Column column = field.getAnnotation(Column.class);
    if (column != null && !column.name().isBlank()) {
      return column.name();
    }
    return field.getName().replaceAll("(?<!^)(?=[A-Z])", "_").toLowerCase(Locale.ROOT);
  }
}
