package com.socialapp.matchmaking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.socialapp.knowledge.entity.enums.SeniorityLevel;
import com.socialapp.matchmaking.entity.ProjectEntity;
import com.socialapp.matchmaking.entity.ProjectPositionEntity;
import com.socialapp.matchmaking.entity.enums.PositionStatus;
import com.socialapp.security.entity.UserEntity;

/**
 * Component (unit) tests for {@link JobDescriptionPdfGenerator}, per ISTQB CTFL v4.0.1 (Section
 * 2.2.1 component testing; Section 4.2.1 equivalence partitioning over the complete / legacy /
 * empty shapes a position can have).
 *
 * <p><b>Assertions stay on ASCII text.</b> Whether Vietnamese survives into the document depends on
 * a font being present on the machine running the test — that fallback is the generator's whole
 * point and cannot be asserted portably. What is asserted instead is that every section reaches the
 * page, that nothing throws on the shapes the database actually holds, and that a very long field
 * wraps onto more pages rather than off the edge of one.
 */
class JobDescriptionPdfGeneratorTest {

  private final JobDescriptionPdfGenerator generator = new JobDescriptionPdfGenerator("");

  private static ProjectEntity project() {
    UserEntity author = new UserEntity();
    author.setId(9001);
    author.setFullName("Owner One");

    ProjectEntity project = new ProjectEntity();
    project.setId(4001);
    project.setTitle("Knowledge Platform");
    project.setDescription("A place teams record technical decisions.");
    project.setCompanyOverview("Six engineers, one product, no meetings before noon.");
    project.setCompanyCulture("Written decisions, reviewed in public.");
    project.setAuthor(author);
    return project;
  }

  private static ProjectPositionEntity position(ProjectEntity project) {
    ProjectPositionEntity position = new ProjectPositionEntity();
    position.setId(30);
    position.setProject(project);
    position.setTitle("Backend Engineer");
    position.setRoleSummary("Own the API layer and the background jobs behind it.");
    position.setResponsibilities(List.of("Design endpoints", "Keep migrations honest"));
    position.setRequirements(List.of("Three years of Java", "Has shipped a REST API"));
    position.setNiceToHave(List.of("Kafka in production"));
    position.setRequiredSkills(List.of("Java", "Spring"));
    position.setQuantity(2);
    position.setSeniorityLevel(SeniorityLevel.SENIOR);
    position.setMinYearsExperience(3);
    position.setStatus(PositionStatus.OPEN);
    return position;
  }

  private static String textOf(byte[] pdf) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      return new PDFTextStripper().getText(document);
    }
  }

  private static int pageCount(byte[] pdf) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      return document.getNumberOfPages();
    }
  }

  @Test
  @DisplayName("should put every section of the job description on the page")
  void shouldRenderEverySection() throws IOException {
    // Given
    ProjectEntity project = project();

    // When
    byte[] pdf = generator.render(project, position(project));

    // Then: a real PDF, and one that carries the whole posting rather than a title page.
    assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    String text = textOf(pdf);
    assertThat(text)
        .contains("Backend Engineer")
        .contains("Own the API layer")
        .contains("Design endpoints")
        .contains("Keep migrations honest")
        .contains("Three years of Java")
        .contains("Kafka in production")
        .contains("Spring")
        .contains("Six engineers")
        .contains("Written decisions");
  }

  @Test
  @DisplayName("should print the seats, the bars and the state under the title")
  void shouldRenderTheMetaLine() throws IOException {
    ProjectEntity project = project();

    String text = textOf(generator.render(project, position(project)));

    assertThat(text).contains("SENIOR").contains("OPEN").contains("Owner One");
  }

  @Test
  @DisplayName("should fall back to the old free-text description when there is no structured JD")
  void shouldRenderLegacyPositions() throws IOException {
    // Given: a position created before V105 — no summary, no responsibilities, no requirements.
    ProjectEntity project = project();
    ProjectPositionEntity legacy = new ProjectPositionEntity();
    legacy.setId(31);
    legacy.setProject(project);
    legacy.setTitle("Frontend Engineer");
    legacy.setDescription("Join the frontend part of the project.");
    legacy.setRequiredSkills(List.of("React"));

    // When
    String text = textOf(generator.render(project, legacy));

    // Then: the useful half, rather than a page of empty headings.
    assertThat(text).contains("Frontend Engineer").contains("Join the frontend part");
  }

  @Test
  @DisplayName("should not print the legacy description once a role summary exists")
  void shouldNotPrintTwoDescriptions() throws IOException {
    // Given: a row carrying both, which is every position edited after V105.
    ProjectEntity project = project();
    ProjectPositionEntity position = position(project);
    position.setDescription("Stale text nobody can edit any more.");

    // When
    String text = textOf(generator.render(project, position));

    // Then: two descriptions of one role, with no way to tell which is current, is worse than one.
    assertThat(text).doesNotContain("Stale text nobody");
  }

  @Test
  @DisplayName("should render a nearly empty position without failing")
  void shouldRenderMinimalPosition() throws IOException {
    // Given: the least a row can hold.
    ProjectEntity project = new ProjectEntity();
    project.setId(4002);
    project.setTitle("Untitled");

    ProjectPositionEntity bare = new ProjectPositionEntity();
    bare.setId(32);
    bare.setProject(project);
    bare.setTitle("Helper");

    // When / Then: a JD endpoint that 500s on an old row is worse than a thin document.
    assertThat(pageCount(generator.render(project, bare))).isEqualTo(1);
  }

  @Test
  @DisplayName("should flow onto more pages rather than off the edge of one")
  void shouldPaginateLongContent() throws IOException {
    ProjectEntity project = project();
    ProjectPositionEntity position = position(project);
    position.setResponsibilities(
        List.of(
            "Design endpoints and the schema behind them, then keep both honest as the product"
                + " changes underneath them week after week after week.",
            "Review the pull requests of everyone else on the team, including the ones nobody"
                + " else wants to read, and leave comments that can be acted on.",
            "Carry the pager one week in four, write the postmortem, and land the fix."));
    position.setRequirements(
        List.of(
            "A very long single token that cannot be broken on a space:"
                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "Three years of Java"));
    project.setCompanyOverview("Paragraph. ".repeat(400));

    byte[] pdf = generator.render(project, position);

    assertThat(pageCount(pdf)).isGreaterThan(1);
    assertThat(textOf(pdf)).contains("Carry the pager");
  }
}
