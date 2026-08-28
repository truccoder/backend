package com.socialapp.bookstore.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.common.enums.LearningCategory;

public interface BookRepository extends JpaRepository<BookEntity, Integer> {

  List<BookEntity> findByAuthorIdOrderByCreatedAtDesc(Integer authorId);

  List<BookEntity> findByPostId(Integer postId);

  List<BookEntity> findByPostIdIn(List<Integer> postIds);

  /**
   * One cursor page of the whole library, newest first, optionally narrowed to one topic.
   *
   * <p>Cursor is the book id, not {@code createdAt} — see {@code BookPageResponseDto} for why. The
   * caller asks for {@code limit + 1} rows and uses the presence of the extra one to answer {@code
   * hasMore} without a second {@code COUNT(*)} over the table.
   *
   * <p>{@code category} null nghĩa là không lọc. Bộ lọc nằm trong chính câu truy vấn này chứ
   * không ở phía trên: trang chỉ có tối đa 50 hàng, nên lọc sau khi cắt trang là lọc trên một
   * mẫu ngẫu nhiên — một chủ đề có sách nhưng không có cuốn nào ở trang đầu sẽ hiện ra rỗng, và
   * cuộn tiếp cũng không cứu được vì mỗi trang lại rỗng theo cùng một kiểu.
   *
   * <p>Cùng dạng {@code :param IS NULL OR ...} với {@code cursor} ngay bên cạnh: Hibernate suy ra
   * kiểu của tham số từ vế so sánh và bind nó là varchar (cột {@code @Enumerated(STRING)}), nên
   * Postgres không rơi vào lỗi "could not determine data type" như với native query.
   * {@code idx_books_category_id} ở V78 phục vụ đúng cặp lọc-rồi-sắp-xếp này.
   */
  @Query(
      """
      SELECT b FROM BookEntity b
      WHERE (:cursor IS NULL OR b.id < :cursor)
        AND (:category IS NULL OR b.category = :category)
      ORDER BY b.id DESC
      """)
  List<BookEntity> findLibraryPage(
      @Param("cursor") Integer cursor,
      @Param("category") LearningCategory category,
      Pageable pageable);

  /**
   * <p>{@code f_unaccent} is the IMMUTABLE wrapper from {@code
   * V48__add_trigram_search_indexes.sql}; the trigram GIN indexes on title and description are
   * built on that exact expression. Calling plain {@code unaccent} here would still return the
   * right rows — it would just quietly stop using the indexes.
   *
   * <p>The {@code cast(... as string)} is Hibernate bookkeeping (a generic {@code function()} has
   * no declared return type, and LIKE needs one). It survives into SQL as a text→varchar relabel,
   * which the planner strips before matching the index expression, so it does not defeat the
   * index.
   */
  @Query(
      """
                      SELECT b FROM BookEntity b
                      WHERE cast(function('f_unaccent', LOWER(b.title)) as string)
                              LIKE cast(function('f_unaccent', LOWER(CONCAT('%', :query, '%'))) as string) ESCAPE '\\'
                         OR cast(function('f_unaccent', LOWER(b.description)) as string)
                              LIKE cast(function('f_unaccent', LOWER(CONCAT('%', :query, '%'))) as string) ESCAPE '\\'
                      ORDER BY b.avgRating DESC, b.reviewCount DESC
                    """)
  Page<BookEntity> search(@Param("query") String query, Pageable pageable);

  /**
   * The type-ahead variant of {@link #search}: title only, and a {@code List} rather than a {@code
   * Page}.
   *
   * <p>Description is left out because a dropdown row shows the title — matching on a word buried
   * in a 2000-character description produces a row whose label contains nothing the user typed,
   * which reads as a broken suggestion. The results page still searches both.
   *
   * <p>{@code Page} would add a {@code COUNT(*)} over every matching book to render a list of five,
   * and no dropdown has a page 2.
   */
  @Query(
      """
                      SELECT b FROM BookEntity b
                      WHERE cast(function('f_unaccent', LOWER(b.title)) as string)
                              LIKE cast(function('f_unaccent', LOWER(CONCAT('%', :query, '%'))) as string) ESCAPE '\\'
                      ORDER BY b.avgRating DESC, b.reviewCount DESC, b.id ASC
                    """)
  List<BookEntity> suggestByTitle(@Param("query") String query, Pageable pageable);
}
