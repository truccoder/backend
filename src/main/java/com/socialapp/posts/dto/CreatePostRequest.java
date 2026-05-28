package com.socialapp.posts.dto;

import com.socialapp.posts.entity.LocationDetails;
import com.socialapp.posts.entity.enums.LocationType;

import jakarta.validation.constraints.NotBlank;

public record CreatePostRequest(
    @NotBlank(message = "content is required") String content,

    String googlePlaceId,

    LocationType locationType,

    LocationDetails location) {}
