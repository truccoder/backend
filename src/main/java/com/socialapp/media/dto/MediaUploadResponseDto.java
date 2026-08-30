package com.socialapp.media.dto;

import java.util.List;

/**
 * The public URLs of the files just stored, in the order they were sent.
 *
 * <p>A list even for a single upload, and wrapped in an object rather than returned as a bare
 * array: the caller pastes these straight into {@code CreatePostRequest.images}, which is a list,
 * and an object leaves room to report per-file detail later without changing the shape of the
 * response.
 */
public record MediaUploadResponseDto(List<String> urls) {}
