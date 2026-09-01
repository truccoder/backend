package com.socialapp.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;

/**
 * Hàng rào cho {@code V94__add_full_text_search_vectors.sql}.
 *
 * <p>Sáu cột {@code search_vector} và sáu index GIN của V94 không có test nào khác canh. Chúng là
 * hạ tầng thuần SQL: không có lớp Java nào đọc chúng ở giai đoạn này, nên một lỗi trong biểu thức
 * generated column sẽ đi qua toàn bộ {@code ./gradlew build} mà không ai biết -- rồi biểu hiện
 * thành "tìm không ra" ở runtime, là loại triệu chứng khó lần nhất.
 *
 * <p><b>Vì sao có test riêng cho {@code coalesce}.</b> {@code f_unaccent} được khai STRICT ở V48,
 * nên NULL vào là NULL ra; {@code to_tsvector('simple', NULL)} là NULL; và {@code NULL || tsvector}
 * là NULL. Bỏ {@code coalesce} ở một vế thôi thì mọi hàng thiếu vế đó mất luôn cả tiêu đề khỏi
 * index. Không lỗi, không cảnh báo -- hàng đó chỉ đơn giản là không bao giờ tìm thấy nữa. Đó là
 * cùng một họ với cái bẫy {@code f_unaccent} so với {@code unaccent} mà V48 đã ghi lại, và cách
 * duy nhất để nó thành build đỏ là một assertion đọc thẳng tsvector của một hàng có NULL.
 *
 * <p>Dùng {@link JdbcTemplate} chứ không qua JPA: đối tượng cần kiểm là chính cái cột và cái
 * index, mà không entity nào map tới -- và cố ý không map, vì {@code search_vector} là dữ liệu
 * dẫn xuất do Postgres giữ, không phải trạng thái ứng dụng ghi.
 */
@Transactional
@DisplayName("V94 -- cột và index full-text search")
class SearchVectorMigrationTest extends AbstractIntegrationTest {

  /** Sáu bảng V94 chạm tới, theo đúng thứ tự trong file migration. */
  private static final List<String> SEARCHABLE_TABLES =
      List.of("t_users", "t_posts", "t_books", "t_projects", "t_roadmaps", "t_trending_items");

  @Autowired private JdbcTemplate jdbc;

  @Nested
  @DisplayName("Cấu trúc")
  class Structure {

    @Test
    @DisplayName("cả sáu bảng đều có cột search_vector kiểu tsvector, và là cột sinh sẵn")
    void everyTableHasAGeneratedTsvectorColumn() {
      for (String table : SEARCHABLE_TABLES) {
        List<String> generated =
            jdbc.queryForList(
                """
                SELECT is_generated FROM information_schema.columns
                WHERE table_schema = 'socialapp' AND table_name = ?
                  AND column_name = 'search_vector' AND udt_name = 'tsvector'
                """,
                String.class,
                table);

        assertThat(generated)
            .as("socialapp.%s.search_vector phải tồn tại, kiểu tsvector", table)
            .hasSize(1);
        assertThat(generated.get(0))
            .as("socialapp.%s.search_vector phải là GENERATED, không phải cột thường", table)
            .isEqualTo("ALWAYS");
      }
    }

    @Test
    @DisplayName("cả sáu bảng đều có index GIN trên search_vector")
    void everyTableHasAGinIndexOnTheVector() {
      for (String table : SEARCHABLE_TABLES) {
        List<String> definitions =
            jdbc.queryForList(
                """
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'socialapp' AND tablename = ?
                  AND indexdef LIKE '%search_vector%'
                """,
                String.class, table);

        assertThat(definitions).as("socialapp.%s thiếu index trên search_vector", table).hasSize(1);
        assertThat(definitions.get(0))
            .as("index của %s phải là GIN -- B-tree không trả lời được @@", table)
            .contains("USING gin");
      }
    }

    @Test
    @DisplayName("sáu index trigram của V48 vẫn còn -- chúng là nhánh dự phòng, không phải rác")
    void v48TrigramIndexesSurvive() {
      Integer remaining =
          jdbc.queryForObject(
              """
              SELECT count(*) FROM pg_indexes
              WHERE schemaname = 'socialapp' AND indexname IN (
                'idx_posts_content_trgm', 'idx_posts_event_title_trgm',
                'idx_books_title_trgm', 'idx_books_description_trgm',
                'idx_users_full_name_trgm', 'idx_users_username_trgm')
              """,
              Integer.class);

      assertThat(remaining)
          .as("V94 chỉ được thêm; xoá index của V48 là bỏ luôn khả năng chịu lỗi chính tả")
          .isEqualTo(6);
    }
  }

  @Nested
  @DisplayName("Bẫy coalesce -- NULL ở một vế không được xoá sổ cả tsvector")
  class NullHandling {

    @Test
    @DisplayName("sách không có mô tả vẫn giữ được tiêu đề trong search_vector")
    void bookWithNullDescriptionStillIndexesItsTitle() {
      Integer authorId =
          jdbc.queryForObject(
              """
              INSERT INTO socialapp.t_users (username, full_name, email, password)
              VALUES ('coalesce-probe', 'Coalesce Probe', 'coalesce-probe@example.com', 'x')
              RETURNING id
              """,
              Integer.class);

      String vector =
          jdbc.queryForObject(
              """
              INSERT INTO socialapp.t_books (author_id, title, description, file_key, file_format)
              VALUES (?, 'Lap trinh Java', NULL, 'books/probe', 'PDF')
              RETURNING search_vector::text
              """,
              String.class,
              authorId);

      assertThat(vector)
          .as("description NULL không được làm cả tsvector thành NULL")
          .isNotNull()
          .contains("java");
    }

    @Test
    @DisplayName("dự án không có tags vẫn giữ được tiêu đề và mô tả")
    void projectWithNullTagsStillIndexesTitleAndDescription() {
      Integer authorId =
          jdbc.queryForObject(
              """
              INSERT INTO socialapp.t_users (username, full_name, email, password)
              VALUES ('tags-probe', 'Tags Probe', 'tags-probe@example.com', 'x')
              RETURNING id
              """,
              Integer.class);

      String vector =
          jdbc.queryForObject(
              """
              INSERT INTO socialapp.t_projects (author_id, title, description, tags, status)
              VALUES (?, 'Cong cu Kotlin', 'Xay bang Spring', NULL, 'OPEN')
              RETURNING search_vector::text
              """,
              String.class,
              authorId);

      assertThat(vector).isNotNull().contains("kotlin").contains("spring");
    }

    @Test
    @DisplayName("bài viết không phải sự kiện (event_details NULL) vẫn giữ được nội dung")
    void postWithoutEventDetailsStillIndexesContent() {
      Integer authorId =
          jdbc.queryForObject(
              """
              INSERT INTO socialapp.t_users (username, full_name, email, password)
              VALUES ('post-probe', 'Post Probe', 'post-probe@example.com', 'x')
              RETURNING id
              """,
              Integer.class);

      String vector =
          jdbc.queryForObject(
              """
              INSERT INTO socialapp.t_posts (author_id, content, visibility, moderation_status)
              VALUES (?, 'Ghi chu ve Postgres', 'PUBLIC', 'APPROVED')
              RETURNING search_vector::text
              """,
              String.class,
              authorId);

      assertThat(vector).isNotNull().contains("postgres");
    }
  }

  @Nested
  @DisplayName("Trọng số và cách tách từ")
  class WeightingAndParsing {

    @Test
    @DisplayName("tiêu đề mang trọng số A, mô tả mang trọng số B")
    void titleOutweighsDescription() {
      Integer authorId =
          jdbc.queryForObject(
              """
              INSERT INTO socialapp.t_users (username, full_name, email, password)
              VALUES ('weight-probe', 'Weight Probe', 'weight-probe@example.com', 'x')
              RETURNING id
              """,
              Integer.class);

      String vector =
          jdbc.queryForObject(
              """
              INSERT INTO socialapp.t_books (author_id, title, description, file_key, file_format)
              VALUES (?, 'Kubernetes', 'Ve Docker', 'books/weight', 'PDF')
              RETURNING search_vector::text
              """,
              String.class,
              authorId);

      // Postgres in trọng số ngay sau vị trí: 'kubernetes':1A còn 'docker':...B
      assertThat(vector).contains("'kubernetes':1A").contains("B");
    }

    @Test
    @DisplayName("dấu tiếng Việt bị gấp phẳng, nên 'lập trình' index thành 'lap' và 'trinh'")
    void vietnameseDiacriticsAreFolded() {
      Integer authorId =
          jdbc.queryForObject(
              """
              INSERT INTO socialapp.t_users (username, full_name, email, password)
              VALUES ('accent-probe', 'Accent Probe', 'accent-probe@example.com', 'x')
              RETURNING id
              """,
              Integer.class);

      String vector =
          jdbc.queryForObject(
              """
              INSERT INTO socialapp.t_books (author_id, title, description, file_key, file_format)
              VALUES (?, 'Lập trình', NULL, 'books/accent', 'PDF')
              RETURNING search_vector::text
              """,
              String.class,
              authorId);

      assertThat(vector).contains("lap").contains("trinh").doesNotContain("lập");
    }

    @Test
    @DisplayName("tags jsonb được tách thành từ, không giữ lại ngoặc và dấu nháy")
    void jsonbTagsBecomeSearchableWords() {
      Integer authorId =
          jdbc.queryForObject(
              """
              INSERT INTO socialapp.t_users (username, full_name, email, password)
              VALUES ('jsonb-probe', 'Jsonb Probe', 'jsonb-probe@example.com', 'x')
              RETURNING id
              """,
              Integer.class);

      String vector =
          jdbc.queryForObject(
              """
              INSERT INTO socialapp.t_projects (author_id, title, description, tags, status)
              VALUES (?, 'Khong lien quan', NULL, '["graphql", "redis"]'::jsonb, 'OPEN')
              RETURNING search_vector::text
              """,
              String.class,
              authorId);

      assertThat(vector).contains("graphql").contains("redis");
    }
  }

  @Nested
  @DisplayName("Truy vấn thật -- những gì LIKE không làm được")
  class QueryBehaviour {

    @Test
    @DisplayName("đa từ khoá: 'java spring' khớp tài liệu chứa cả hai từ, thứ tự nào cũng được")
    void multipleKeywordsMatchRegardlessOfOrder() {
      Integer authorId = seedAuthor("multi-probe");
      seedBook(authorId, "Spring Boot toan tap", "Dung Java 17", "books/multi-1");
      seedBook(authorId, "Chi noi ve Python", "Khong lien quan", "books/multi-2");

      List<String> titles =
          jdbc.queryForList(
              """
              SELECT title FROM socialapp.t_books
              WHERE search_vector @@ websearch_to_tsquery('simple', 'java spring')
                AND author_id = ?
              """,
              String.class,
              authorId);

      assertThat(titles).containsExactly("Spring Boot toan tap");
    }

    @Test
    @DisplayName("cụm trong ngoặc kép chỉ khớp khi hai từ đứng liền nhau")
    void quotedPhraseRequiresAdjacency() {
      Integer authorId = seedAuthor("phrase-probe");
      seedBook(authorId, "Spring Boot", "Lien nhau", "books/phrase-1");
      seedBook(authorId, "Spring va Boot", "Cach nhau mot tu", "books/phrase-2");

      List<String> titles =
          jdbc.queryForList(
              """
              SELECT title FROM socialapp.t_books
              WHERE search_vector @@ websearch_to_tsquery('simple', '"spring boot"')
                AND author_id = ?
              """,
              String.class,
              authorId);

      assertThat(titles).containsExactly("Spring Boot");
    }

    @Test
    @DisplayName("ts_rank_cd xếp khớp tiêu đề trên khớp mô tả")
    void titleMatchRanksAboveDescriptionMatch() {
      Integer authorId = seedAuthor("rank-probe");
      seedBook(authorId, "Chi trong mo ta", "Cuon nay noi ve Rust", "books/rank-1");
      seedBook(authorId, "Rust thuc chien", "Khong nhac ten do o day", "books/rank-2");

      List<String> ordered =
          jdbc.queryForList(
              """
              SELECT title FROM socialapp.t_books, websearch_to_tsquery('simple', 'rust') q
              WHERE search_vector @@ q AND author_id = ?
              ORDER BY ts_rank_cd(search_vector, q, 32) DESC, id ASC
              """,
              String.class,
              authorId);

      assertThat(ordered).containsExactly("Rust thuc chien", "Chi trong mo ta");
    }

    @Test
    @DisplayName("điểm sau chuẩn hoá cờ 32 luôn nằm trong khoảng (0,1)")
    void normalisedScoreStaysInUnitInterval() {
      Integer authorId = seedAuthor("score-probe");
      seedBook(authorId, "Elixir", "Elixir Elixir Elixir Elixir Elixir", "books/score-1");

      Double score =
          jdbc.queryForObject(
              """
              SELECT ts_rank_cd(search_vector, q, 32)
              FROM socialapp.t_books, websearch_to_tsquery('simple', 'elixir') q
              WHERE search_vector @@ q AND author_id = ?
              """,
              Double.class,
              authorId);

      assertThat(score).isNotNull().isGreaterThan(0.0).isLessThan(1.0);
    }

    @Test
    @DisplayName("tìm không dấu vẫn ra tài liệu có dấu -- hàng rào hồi quy cho f_unaccent")
    void unaccentedQueryStillFindsAccentedText() {
      Integer authorId = seedAuthor("regress-probe");
      seedBook(authorId, "Lập trình Java", "Sách tiếng Việt", "books/regress-1");

      List<String> titles =
          jdbc.queryForList(
              """
              SELECT title FROM socialapp.t_books
              WHERE search_vector @@ websearch_to_tsquery('simple', 'lap trinh')
                AND author_id = ?
              """,
              String.class,
              authorId);

      assertThat(titles).containsExactly("Lập trình Java");
    }
  }

  private Integer seedAuthor(String username) {
    return jdbc.queryForObject(
        """
        INSERT INTO socialapp.t_users (username, full_name, email, password)
        VALUES (?, 'Probe User', ? || '@example.com', 'x')
        RETURNING id
        """,
        Integer.class,
        username,
        username);
  }

  private void seedBook(Integer authorId, String title, String description, String fileKey) {
    jdbc.update(
        """
        INSERT INTO socialapp.t_books (author_id, title, description, file_key, file_format)
        VALUES (?, ?, ?, ?, 'PDF')
        """,
        authorId,
        title,
        description,
        fileKey);
  }
}
