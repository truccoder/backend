package com.socialapp.posts.dto;

import com.socialapp.posts.entity.LocationDetails;
import com.socialapp.posts.entity.enums.LocationType;

/**
 * Mirrors the {@code googlePlaceId}/{@code locationType}/{@code locationDetails} shape of {@link
 * CreatePostRequestDto} so the frontend can pass this response straight back unchanged when
 * creating the post. {@code googleMapsUrl} is extra (not part of that round-trip shape) — a plain
 * deep link for the frontend to let the user open the location in Google Maps.
 */
public record LocationResolutionResponseDto(
    String googlePlaceId,
    LocationType locationType,
    LocationDetails locationDetails,
    String googleMapsUrl) {}
