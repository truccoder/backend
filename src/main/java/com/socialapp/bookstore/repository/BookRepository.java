package com.socialapp.bookstore.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.bookstore.entity.BookEntity;

public interface BookRepository extends JpaRepository<BookEntity, Integer> {

  List<BookEntity> findByAuthorIdOrderByCreatedAtDesc(Integer authorId);

  List<BookEntity> findByPostId(Integer postId);

  List<BookEntity> findByPostIdIn(List<Integer> postIds);

  @Query(
      """
                      SELECT b FROM BookEntity b
                      WHERE cast(function('unaccent', LOWER(b.title)) as string)
                              LIKE cast(function('unaccent', LOWER(CONCAT('%', :query, '%'))) as string) ESCAPE '\\'
                         OR cast(function('unaccent', LOWER(b.description)) as string)
                              LIKE cast(function('unaccent', LOWER(CONCAT('%', :query, '%'))) as string) ESCAPE '\\'
                      ORDER BY b.avgRating DESC, b.reviewCount DESC
                    """)
  Page<BookEntity> search(@Param("query") String query, Pageable pageable);
}
