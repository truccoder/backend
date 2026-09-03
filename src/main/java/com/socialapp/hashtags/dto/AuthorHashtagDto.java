package com.socialapp.hashtags.dto;

/**
 * One (author, hashtag) pair carried by a PUBLIC, APPROVED post — see {@code
 * HashtagRepository.findHashtagsByAuthors}, which this backs.
 */
public record AuthorHashtagDto(Integer authorId, String hashtagName) {}
