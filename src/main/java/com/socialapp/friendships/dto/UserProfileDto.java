package com.socialapp.friendships.dto;

public record UserProfileDto(
    Integer userId, String username, String fullName, String profilePictureUrl) {}
