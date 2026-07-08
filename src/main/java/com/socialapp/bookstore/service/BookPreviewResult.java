package com.socialapp.bookstore.service;

/**
 * {@code totalUnits} is total PDF pages or total EPUB chapters, depending on the source format.
 */
public record BookPreviewResult(byte[] previewBytes, int totalUnits) {}
