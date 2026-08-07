package com.socialapp.posts.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.socialapp.posts.entity.HashtagEntity;

@Repository
public interface HashtagRepository extends JpaRepository<HashtagEntity, Integer> {
  Optional<HashtagEntity> findByName(String name);

  List<HashtagEntity> findByNameIn(Collection<String> names);
}
