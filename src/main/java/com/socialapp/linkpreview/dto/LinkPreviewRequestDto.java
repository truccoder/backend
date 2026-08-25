package com.socialapp.linkpreview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The link to look at.
 *
 * <p>A POST body rather than a query parameter, even though this reads and changes nothing. A URL
 * in the query string is written into every access log and proxy cache along the way, and the
 * whole point of this endpoint is that the URL comes from an outside party who has not decided yet
 * whether to publish it.
 */
public record LinkPreviewRequestDto(
    @NotBlank(message = "url is required")
        @Size(max = 2048, message = "url must be at most 2048 characters")
        String url) {}
