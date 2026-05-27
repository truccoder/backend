package com.socialapp.posts.dto;

import com.socialapp.posts.entity.LocationDetails;
import com.socialapp.posts.entity.enums.PostVisibility;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreatePostRequest(
    String content,

    @NotNull(message = "visibility is required")
    PostVisibility visibility,

    List<String> images,

    LocationDetails location) {}
