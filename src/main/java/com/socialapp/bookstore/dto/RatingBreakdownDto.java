package com.socialapp.bookstore.dto;

public record RatingBreakdownDto(
    long oneStarCount,
    long twoStarsCount,
    long threeStarsCount,
    long fourStarsCount,
    long fiveStarsCount,
    long totalRatings) {}
