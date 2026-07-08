package com.socialapp.posts.dto;

/**
 * Either {@code query} (freeform place name/address) or both {@code latitude} and {@code
 * longitude} (reverse geocoding) must be provided; validated in {@code LocationResolutionService}.
 */
public record LocationResolutionRequestDto(String query, Double latitude, Double longitude) {}
