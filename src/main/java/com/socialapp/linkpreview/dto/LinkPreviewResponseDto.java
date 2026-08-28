package com.socialapp.linkpreview.dto;

/**
 * What the target page says about itself — the four fields {@code LinkDetails} carries, in the same
 * order, so the composer can fill its form from this response one-to-one.
 *
 * <p>Every field is nullable and a response with all four null is a normal outcome, not an error: a
 * page is under no obligation to describe itself, and the composer's job is to prefill what it can
 * and leave the author to type the rest. That is the same posture the client already had to take,
 * since these fields were typed by hand before this endpoint existed.
 */
public record LinkPreviewResponseDto(
    String title, String description, String thumbnailUrl, String siteName) {}
