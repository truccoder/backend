package com.socialapp.bookstore.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

import nl.siegmann.epublib.domain.Book;
import nl.siegmann.epublib.domain.Resource;
import nl.siegmann.epublib.domain.SpineReference;
import nl.siegmann.epublib.epub.EpubReader;
import nl.siegmann.epublib.epub.EpubWriter;

/**
 * Produces a trimmed copy of a book's file containing only the first {@code previewPages}
 * pages (PDF) or chapters (EPUB), so the "preview" served to non-purchasers genuinely cannot
 * contain more than that, rather than a presigned link to the full file with a client-side page
 * limit that could be bypassed.
 */
@Component
public class BookPreviewGenerator {

  public int countPdfPages(byte[] original) throws IOException {
    try (PDDocument source = Loader.loadPDF(original)) {
      return source.getNumberOfPages();
    }
  }

  public int countEpubChapters(byte[] original) throws IOException {
    Book source = new EpubReader().readEpub(new ByteArrayInputStream(original));
    return source.getSpine().getSpineReferences().size();
  }

  public BookPreviewResult generatePdfPreview(byte[] original, int previewPages)
      throws IOException {
    try (PDDocument source = Loader.loadPDF(original)) {
      int totalPages = source.getNumberOfPages();
      int pagesToKeep = Math.max(1, Math.min(previewPages, totalPages));

      try (PDDocument preview = new PDDocument()) {
        for (int i = 0; i < pagesToKeep; i++) {
          preview.importPage(source.getPage(i));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        preview.save(out);
        return new BookPreviewResult(out.toByteArray(), totalPages);
      }
    }
  }

  public BookPreviewResult generateEpubPreview(byte[] original, int previewChapters)
      throws IOException {
    Book source = new EpubReader().readEpub(new ByteArrayInputStream(original));
    List<SpineReference> spineRefs = source.getSpine().getSpineReferences();
    int totalChapters = spineRefs.size();
    int chaptersToKeep = Math.max(1, Math.min(previewChapters, totalChapters));

    // Chapters beyond the cut-off must not even be present as raw resources in the preview
    // archive, or someone could just unzip the epub and read them directly.
    Set<String> excludedHrefs = new HashSet<>();
    for (int i = chaptersToKeep; i < totalChapters; i++) {
      excludedHrefs.add(spineRefs.get(i).getResource().getHref());
    }

    Book preview = new Book();
    preview.setMetadata(source.getMetadata());
    if (source.getCoverImage() != null) {
      preview.setCoverImage(source.getCoverImage());
    }

    for (Resource resource : source.getResources().getAll()) {
      if (!excludedHrefs.contains(resource.getHref())) {
        preview.getResources().add(resource);
      }
    }

    for (int i = 0; i < chaptersToKeep; i++) {
      preview.getSpine().addSpineReference(new SpineReference(spineRefs.get(i).getResource()));
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    new EpubWriter().write(preview, out);
    return new BookPreviewResult(out.toByteArray(), totalChapters);
  }
}
