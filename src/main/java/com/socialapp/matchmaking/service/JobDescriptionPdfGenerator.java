package com.socialapp.matchmaking.service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.socialapp.common.exception.StorageException;
import com.socialapp.matchmaking.entity.ProjectEntity;
import com.socialapp.matchmaking.entity.ProjectPositionEntity;

import lombok.extern.slf4j.Slf4j;

/**
 * Renders one role's job description as a PDF, laid out the way a JD is read: the role first, then
 * what it involves and what it asks for, then who is asking.
 *
 * <p><b>Why a PDF at all</b>, when the same fields are already on the JSON. Because a JD is the one
 * thing on a project board that leaves it: it gets forwarded, printed, attached to a message and
 * read by someone who was never going to sign in. The bookstore already serves exactly this shape —
 * a generated PDF in MinIO behind a presigned URL — so a role opens in the same viewer a book
 * preview does rather than in a second, half-built one.
 *
 * <p><b>Fonts, and the diacritics problem.</b> PDFBox's built-in Helvetica is WinAnsi: it cannot
 * encode "Phát triển" and throws at {@code showText} rather than dropping the character. Projects
 * here are written in Vietnamese, so this embeds a real Unicode TrueType font when it can find one
 * — {@code matchmaking.job-description.font-path} first, then the usual system locations, with
 * DejaVu installed in the runtime image for exactly this. Only if nothing is found does it fall
 * back to Helvetica <em>with the text folded to ASCII</em> ({@code MinIOSeedObjectInitializer}
 * makes the same trade for the same reason): a JD reading "Phat trien tinh nang" is poor, and a 503
 * where the JD should be is worse.
 */
@Slf4j
@Component
public class JobDescriptionPdfGenerator {

  private static final PDRectangle PAGE_SIZE = PDRectangle.A4;
  private static final float MARGIN = 56f;
  private static final float TITLE_SIZE = 20f;
  private static final float HEADING_SIZE = 12.5f;
  private static final float BODY_SIZE = 10.5f;
  private static final float LINE_GAP = 1.45f;
  private static final float SECTION_GAP = 14f;
  private static final String BULLET = "\u2022  ";

  /**
   * Where to look for a Unicode font, in order. These are where DejaVu, Liberation and the Windows
   * core fonts actually live on the two systems this runs on: the Debian-based runtime image, and a
   * developer's Windows machine.
   */
  private static final List<String> SYSTEM_FONT_CANDIDATES =
      List.of(
          "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
          "/usr/share/fonts/dejavu/DejaVuSans.ttf",
          "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
          "C:/Windows/Fonts/arial.ttf",
          "C:/Windows/Fonts/segoeui.ttf");

  private final String configuredFontPath;

  public JobDescriptionPdfGenerator(
      @Value("${matchmaking.job-description.font-path:}") String configuredFontPath) {
    this.configuredFontPath = configuredFontPath;
  }

  /**
   * The JD of {@code position} on {@code project}, as PDF bytes.
   *
   * <p>Both arguments must be loaded — this reads the project's author — so it is called from
   * inside the transaction that fetched them (see {@code JobDescriptionService}).
   *
   * <p>Empty sections are skipped rather than printed as bare headings: positions created before
   * {@code V105} have no structured JD at all, and for those this still produces the useful half
   * (title, skills, the old free-text description) instead of a page of empty labels.
   */
  public byte[] render(ProjectEntity project, ProjectPositionEntity position) {
    try (PDDocument document = new PDDocument()) {
      Fonts fonts = loadFonts(document);
      Layout layout = new Layout(document, fonts);

      layout.label("VI TRI TUYEN — " + safeUpper(project.getTitle()));
      layout.title(position.getTitle());
      layout.meta(metaLine(project, position));
      layout.rule();

      layout.paragraphSection("Tóm tắt vị trí", position.getRoleSummary());
      layout.bulletSection("Công việc chính", position.getResponsibilities());
      layout.bulletSection("Yêu cầu", position.getRequirements());
      layout.bulletSection("Điểm cộng", position.getNiceToHave());
      layout.bulletSection("Kỹ năng cần có", position.getRequiredSkills());

      // Only when there is no structured JD. On a position written after V105 this column is text
      // the owner has had no way to edit since, and printing both would show two descriptions of
      // one role with no way to tell which is current.
      if (isBlank(position.getRoleSummary())) {
        layout.paragraphSection("Mô tả", position.getDescription());
      }

      layout.paragraphSection("Về nhóm dự án", project.getCompanyOverview());
      layout.paragraphSection("Văn hoá làm việc", project.getCompanyCulture());
      layout.paragraphSection("Giới thiệu dự án", project.getDescription());

      layout.close();

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      // Same reasoning as MinIOService: nothing here is separately actionable — the bytes were not
      // produced and the request cannot be served — and StorageException already maps to the 503
      // that means "downstream trouble, retry", which is true of a font that failed to load.
      throw new StorageException("Could not render the job description PDF", e);
    }
  }

  /** The one line under the title: how many seats, what bar, what state the role is in. */
  private static String metaLine(ProjectEntity project, ProjectPositionEntity position) {
    List<String> parts = new ArrayList<>();
    if (position.getQuantity() != null) {
      parts.add("Số lượng: " + position.getQuantity());
    }
    if (position.getSeniorityLevel() != null) {
      parts.add("Cấp độ: " + position.getSeniorityLevel().name());
    }
    if (position.getMinYearsExperience() != null) {
      parts.add("Kinh nghiệm tối thiểu: " + position.getMinYearsExperience() + " năm");
    }
    if (position.getStatus() != null) {
      parts.add("Trạng thái: " + position.getStatus().name());
    }
    if (project.getAuthor() != null && project.getAuthor().getFullName() != null) {
      parts.add("Người đăng: " + project.getAuthor().getFullName());
    }
    return String.join("   -   ", parts);
  }

  // ── Fonts ───────────────────────────────────────────────────────────────────────────────────

  /**
   * The regular/bold pair used for the whole document, and whether text has to be folded to ASCII
   * before it can be written with them.
   */
  private record Fonts(PDFont regular, PDFont bold, boolean asciiOnly) {}

  private Fonts loadFonts(PDDocument document) {
    String path = firstReadableFont();
    if (path != null) {
      try {
        // Embedded and subset: the reader needs no font installed, and only the glyphs actually
        // used travel with the file.
        PDFont regular = PDType0Font.load(document, new File(path));
        PDFont bold = loadBoldNextTo(document, path, regular);
        return new Fonts(regular, bold, false);
      } catch (IOException e) {
        log.warn("Could not embed font '{}', falling back to Helvetica: {}", path, e.getMessage());
      }
    } else {
      log.warn(
          "No Unicode font found for job description PDFs; text will be folded to ASCII. Set"
              + " matchmaking.job-description.font-path to a .ttf to fix this.");
    }
    return new Fonts(
        new PDType1Font(Standard14Fonts.FontName.HELVETICA),
        new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),
        true);
  }

  /**
   * The bold face sitting beside the regular one, by the naming DejaVu, Liberation and the Windows
   * core fonts use. Falls back to the regular face rather than to Helvetica-Bold: mixing an
   * embedded Unicode font with a WinAnsi one inside one document reintroduces the exact crash on
   * the first heading that contains a diacritic.
   */
  private PDFont loadBoldNextTo(PDDocument document, String regularPath, PDFont fallback) {
    for (String suffix : List.of("-Bold.ttf", "bd.ttf")) {
      String candidate = regularPath.replaceAll("(-Regular)?\\.ttf$", suffix);
      File file = new File(candidate);
      if (!candidate.equals(regularPath) && file.isFile()) {
        try {
          return PDType0Font.load(document, file);
        } catch (IOException e) {
          log.debug("Bold face '{}' not usable: {}", candidate, e.getMessage());
        }
      }
    }
    return fallback;
  }

  private String firstReadableFont() {
    if (configuredFontPath != null && !configuredFontPath.isBlank()) {
      File configured = new File(configuredFontPath.trim());
      if (configured.isFile()) {
        return configured.getPath();
      }
      log.warn("matchmaking.job-description.font-path '{}' is not a file", configuredFontPath);
    }
    return SYSTEM_FONT_CANDIDATES.stream()
        .filter(path -> new File(path).isFile())
        .findFirst()
        .orElse(null);
  }

  // ── Layout ──────────────────────────────────────────────────────────────────────────────────

  /**
   * A cursor down an A4 page that opens a new page when it runs out of room.
   *
   * <p>Every method here writes already-encodable text: {@link Layout#encode} runs on the way in,
   * so no caller has to remember whether the document ended up on an embedded font or on Helvetica.
   */
  private final class Layout {
    private final PDDocument document;
    private final Fonts fonts;
    private final float contentWidth;
    private PDPageContentStream stream;
    private float y;

    private Layout(PDDocument document, Fonts fonts) throws IOException {
      this.document = document;
      this.fonts = fonts;
      this.contentWidth = PAGE_SIZE.getWidth() - 2 * MARGIN;
      newPage();
    }

    private void newPage() throws IOException {
      if (stream != null) {
        stream.close();
      }
      PDPage page = new PDPage(PAGE_SIZE);
      document.addPage(page);
      stream = new PDPageContentStream(document, page);
      y = PAGE_SIZE.getHeight() - MARGIN;
    }

    private void close() throws IOException {
      if (stream != null) {
        stream.close();
        stream = null;
      }
    }

    /** The kicker above the title. */
    private void label(String text) throws IOException {
      write(text, fonts.bold(), 8.5f);
      y -= 6f;
    }

    private void title(String text) throws IOException {
      write(text, fonts.bold(), TITLE_SIZE);
      y -= 2f;
    }

    private void meta(String text) throws IOException {
      if (!isBlank(text)) {
        write(text, fonts.regular(), 9f);
      }
    }

    private void rule() throws IOException {
      ensureRoom(SECTION_GAP);
      y -= 8f;
      stream.moveTo(MARGIN, y);
      stream.lineTo(PAGE_SIZE.getWidth() - MARGIN, y);
      stream.setLineWidth(0.6f);
      stream.stroke();
      y -= SECTION_GAP;
    }

    private void paragraphSection(String heading, String body) throws IOException {
      if (isBlank(body)) {
        return;
      }
      writeHeading(heading);
      for (String paragraph : body.split("\\r?\\n")) {
        if (!paragraph.isBlank()) {
          write(paragraph.trim(), fonts.regular(), BODY_SIZE);
        }
      }
      y -= SECTION_GAP;
    }

    private void bulletSection(String heading, List<String> items) throws IOException {
      List<String> present =
          items == null
              ? List.of()
              : items.stream().filter(Objects::nonNull).filter(item -> !item.isBlank()).toList();
      if (present.isEmpty()) {
        return;
      }
      writeHeading(heading);
      for (String item : present) {
        writeBullet(item.trim());
      }
      y -= SECTION_GAP;
    }

    private void writeHeading(String heading) throws IOException {
      // Keeps a heading from being the last thing on a page with its first line overleaf.
      ensureRoom(HEADING_SIZE * 3);
      write(heading, fonts.bold(), HEADING_SIZE);
      y -= 3f;
    }

    /** Wrapped text at the left margin. */
    private void write(String text, PDFont font, float size) throws IOException {
      writeWrapped(text, font, size, 0f);
    }

    /** A bullet whose continuation lines hang under the text rather than under the dot. */
    private void writeBullet(String text) throws IOException {
      float indent = stringWidth(fonts.regular(), BULLET, BODY_SIZE);
      writeWrapped(BULLET + text, fonts.regular(), BODY_SIZE, indent);
    }

    private void writeWrapped(String text, PDFont font, float size, float hangingIndent)
        throws IOException {
      float lineHeight = size * LINE_GAP;
      boolean first = true;
      for (String line : wrap(encode(text), font, size, hangingIndent)) {
        ensureRoom(lineHeight);
        float x = MARGIN + (first ? 0f : hangingIndent);
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(x, y - size);
        stream.showText(line);
        stream.endText();
        y -= lineHeight;
        first = false;
      }
    }

    private void ensureRoom(float needed) throws IOException {
      if (y - needed < MARGIN) {
        newPage();
      }
    }

    /**
     * Greedy word wrap. A word wider than a whole line (a URL, a pasted token) is broken by
     * character — without that branch the loop would emit an empty line forever.
     */
    private List<String> wrap(String text, PDFont font, float size, float hangingIndent) {
      List<String> lines = new ArrayList<>();
      StringBuilder current = new StringBuilder();

      for (String word : text.split("\\s+")) {
        if (word.isEmpty()) {
          continue;
        }
        float available = lines.isEmpty() ? contentWidth : contentWidth - hangingIndent;
        String candidate = current.length() == 0 ? word : current + " " + word;
        if (stringWidth(font, candidate, size) <= available) {
          current.setLength(0);
          current.append(candidate);
          continue;
        }
        if (current.length() > 0) {
          lines.add(current.toString());
          current.setLength(0);
        }
        if (stringWidth(font, word, size) <= contentWidth - hangingIndent) {
          current.append(word);
        } else {
          List<String> pieces = breakLongWord(word, font, size, contentWidth - hangingIndent);
          lines.addAll(pieces.subList(0, pieces.size() - 1));
          current.append(pieces.get(pieces.size() - 1));
        }
      }
      if (current.length() > 0) {
        lines.add(current.toString());
      }
      return lines.isEmpty() ? List.of("") : lines;
    }

    private List<String> breakLongWord(String word, PDFont font, float size, float limit) {
      List<String> pieces = new ArrayList<>();
      StringBuilder piece = new StringBuilder();
      for (char c : word.toCharArray()) {
        if (piece.length() > 0 && stringWidth(font, piece.toString() + c, size) > limit) {
          pieces.add(piece.toString());
          piece.setLength(0);
        }
        piece.append(c);
      }
      if (piece.length() > 0) {
        pieces.add(piece.toString());
      }
      return pieces.isEmpty() ? List.of(word) : pieces;
    }

    /**
     * Width in points, defensively: {@code getStringWidth} throws on any character the font cannot
     * encode, and a wrapping measurement is not the place to discover that. A rough estimate makes
     * one line the wrong length; an exception loses the whole document.
     */
    private float stringWidth(PDFont font, String text, float size) {
      try {
        return font.getStringWidth(text) / 1000f * size;
      } catch (IOException | IllegalArgumentException e) {
        return text.length() * size * 0.5f;
      }
    }

    /** Text as this document's fonts can actually write it. */
    private String encode(String text) {
      String cleaned = text == null ? "" : text.replaceAll("[\\p{Cntrl}]", " ");
      return fonts.asciiOnly() ? asciiSafe(cleaned) : cleaned;
    }
  }

  // ── Text helpers ────────────────────────────────────────────────────────────────────────────

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static String safeUpper(String s) {
    return s == null ? "" : s.toUpperCase();
  }

  /**
   * Drops diacritics and the curly punctuation Helvetica cannot encode. Only reached on the
   * fallback path, where the alternative is not "a nicer PDF" but no PDF at all.
   */
  private static String asciiSafe(String s) {
    String folded =
        Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replaceAll("\\p{M}", "")
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .replace('\u201C', '"')
            .replace('\u201D', '"')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replace("\u2022", "*")
            .replace("\u00b7", "-");
    return folded.replaceAll("[^\\x20-\\x7E]", "?");
  }
}
