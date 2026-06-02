package com.socialapp.bookstore.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.bookstore.entity.BookEntity;

public interface BookRepository extends JpaRepository<BookEntity, Integer> {

  List<BookEntity> findByAuthorIdOrderByCreatedAtDesc(Integer authorId);

  List<BookEntity> findByPostId(Integer postId);
}
