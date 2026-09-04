package com.socialapp.matchmaking.dto;

import java.time.OffsetDateTime;

/**
 * Where to open a role's job description, and when the document was produced.
 *
 * <p>Same shape as {@code DownloadUrlResponse} in the bookstore, with {@code renderedAt} added:
 * the URL is a presigned link that expires, so a client caching the response needs to know how old
 * the thing behind it is rather than assuming the link is permanent.
 */
public record JobDescriptionUrlResponse(String url, OffsetDateTime renderedAt) {}
