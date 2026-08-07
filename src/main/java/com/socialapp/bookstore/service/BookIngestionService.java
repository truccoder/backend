package com.socialapp.bookstore.service;

import java.io.IOException;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.bookstore.dto.CreateBookRequestDto;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.entity.enums.FileFormat;
import com.socialapp.bookstore.repository.BookRepository;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.common.utils.FileExtensions;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Turning an uploaded file into a book row: validation, page counting, preview generation, the
 * uploads, and the insert.
 *
 * <p>Split out of {@code BookService}, which had grown into everything a book can do — creation,
 * reads, signed URLs, deletion — and was flagged as a God Class for it. The write path is the part
 * with its own vocabulary (formats, previews, buckets) and its own collaborators, so it is the
 * seam that separates cleanly. {@code BookService} still fronts creation for callers, so nothing
 * outside this package had to learn a new name.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookIngestionService {
  private final BookRepository bookRepository;
  private final BookStorageService bookStorageService;
  private final BookPreviewGenerator bookPreviewGenerator;

  private static final List<String> ALLOWED_FORMATS = List.of("pdf", "epub");

  // Carries the bytes rather than an object key: generating a preview must not have
  // uploaded anything yet, since this is the step that can still reject the request.
  private record GeneratedPreview(byte[] previewBytes, int totalUnits) {}

  /**
   * What the uploads produced. Grouped into one value rather than passed as three loose strings:
   * they are written together, cleaned up together, and only mean anything together.
   */
  private record StoredFiles(String fileKey, String previewFileKey, String coverKey) {}

  /**
   * What inspecting the file established, before anything was stored. Together with {@link
   * StoredFiles} this replaces the ten-parameter {@code saveBook} that PMD flagged — ten
   * positional arguments of which four were {@code String} is a call site nobody can read.
   */
  private record BookFacts(
      FileFormat format, boolean isFree, Integer totalPages, long fileSizeBytes) {}

  /** Creates the book row for a post, uploading its file, preview and cover on the way. */
  @Transactional
  public BookEntity ingest(
      Integer authorId,
      Integer postId,
      CreateBookRequestDto request,
      MultipartFile bookFile,
      MultipartFile coverFile) {
    // Everything that can reject the request runs before the first byte reaches MinIO. Uploads
    // used to come first, and because MinIO is not part of the surrounding transaction, a book
    // rejected for its previewPages left its file (and cover) in the bucket forever while
    // Postgres rolled back cleanly — measured at 4 objects for 2 books.
    validateFile(bookFile);

    FileFormat format = resolveFormat(bookFile.getOriginalFilename());
    boolean isFree = request.getPrice() == null || request.getPrice() <= 0;

    if (!isFree && (request.getPreviewPages() == null || request.getPreviewPages() <= 0)) {
      throw new ValidationException("Paid books must have preview pages configured");
    }

    Integer totalPages;
    byte[] previewBytes = null;

    if (isFree) {
      totalPages = countPages(format, bookFile);
    } else {
      // Generated in memory here, uploaded below: this is the step that rejects a previewPages
      // value larger than the book itself, and it must be able to do so with nothing uploaded.
      GeneratedPreview preview = generatePreview(format, bookFile, request.getPreviewPages());
      previewBytes = preview.previewBytes();
      totalPages = preview.totalUnits();
    }

    BookFacts facts = new BookFacts(format, isFree, totalPages, bookFile.getSize());

    // Past this line uploads begin. Validation cannot fail any more, but MinIO or the insert
    // still can, so whatever landed is removed on the way out.
    String fileKey = null;
    String previewFileKey = null;
    String coverKey = null;

    try {
      fileKey = bookStorageService.uploadBook(authorId, bookFile);

      if (previewBytes != null) {
        String extension = format == FileFormat.EPUB ? "epub" : "pdf";
        previewFileKey = bookStorageService.uploadPreview(authorId, previewBytes, extension);
      }

      if (coverFile != null && !coverFile.isEmpty()) {
        coverKey = bookStorageService.uploadCover(authorId, coverFile);
      }

      return saveBook(
          authorId, postId, request, facts, new StoredFiles(fileKey, previewFileKey, coverKey));
    } catch (RuntimeException e) {
      bookStorageService.deleteQuietly(bookStorageService.booksBucket(), fileKey);
      bookStorageService.deleteQuietly(bookStorageService.booksBucket(), previewFileKey);
      bookStorageService.deleteQuietly(bookStorageService.coversBucket(), coverKey);
      throw e;
    }
  }

  private BookEntity saveBook(
      Integer authorId,
      Integer postId,
      CreateBookRequestDto request,
      BookFacts facts,
      StoredFiles files) {
    BookEntity book =
        BookEntity.builder()
            .authorId(authorId)
            .postId(postId)
            .title(request.getTitle())
            .description(request.getDescription())
            .fileKey(files.fileKey())
            .previewFileKey(files.previewFileKey())
            .coverImageKey(files.coverKey())
            .fileFormat(facts.format())
            .fileSizeBytes(facts.fileSizeBytes())
            .totalPages(facts.totalPages())
            .previewPages(facts.isFree() ? 0 : request.getPreviewPages())
            .price(facts.isFree() ? 0L : request.getPrice())
            .isFree(facts.isFree())
            .build();

    bookRepository.save(book);
    return book;
  }

  private int countPages(FileFormat format, MultipartFile bookFile) {
    try {
      byte[] original = bookFile.getBytes();
      return format == FileFormat.EPUB
          ? bookPreviewGenerator.countEpubChapters(original)
          : bookPreviewGenerator.countPdfPages(original);
    } catch (IOException e) {
      throw new ValidationException("Book file is corrupted or not a valid " + format, e);
    }
  }

  /**
   * Generates and uploads a trimmed preview containing only the first {@code previewPages}
   * pages/chapters, so previewing a paid book can never expose more than that regardless of what
   * URL the client has.
   */
  private GeneratedPreview generatePreview(
      FileFormat format, MultipartFile bookFile, int previewPages) {
    try {
      byte[] original = bookFile.getBytes();
      BookPreviewResult result =
          format == FileFormat.EPUB
              ? bookPreviewGenerator.generateEpubPreview(original, previewPages)
              : bookPreviewGenerator.generatePdfPreview(original, previewPages);

      if (previewPages >= result.totalUnits()) {
        throw new ValidationException(
            "Preview pages/chapters ("
                + previewPages
                + ") must be less than the book's total ("
                + result.totalUnits()
                + ")");
      }

      return new GeneratedPreview(result.previewBytes(), result.totalUnits());
    } catch (IOException e) {
      throw new ValidationException("Book file is corrupted or not a valid " + format, e);
    }
  }

  private void validateFile(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new ValidationException("Book file is required");
    }
    String ext = FileExtensions.getExtension(file.getOriginalFilename(), "");
    if (!ALLOWED_FORMATS.contains(ext)) {
      throw new ValidationException("Only PDF and EPUB formats are supported");
    }
  }

  private FileFormat resolveFormat(String filename) {
    String ext = FileExtensions.getExtension(filename, "");
    return "epub".equals(ext) ? FileFormat.EPUB : FileFormat.PDF;
  }
}
