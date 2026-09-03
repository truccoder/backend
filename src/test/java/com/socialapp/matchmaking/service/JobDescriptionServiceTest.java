package com.socialapp.matchmaking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.matchmaking.dto.JobDescriptionUrlResponse;
import com.socialapp.matchmaking.entity.ProjectEntity;
import com.socialapp.matchmaking.entity.ProjectPositionEntity;
import com.socialapp.matchmaking.repository.ProjectPositionRepository;

/**
 * Component (unit) tests for {@link JobDescriptionService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing; Section 4.3.2 branch testing over every arm of the staleness decision).
 *
 * <p>The cache is the whole behaviour worth pinning down here, and each arm of it is a distinct
 * failure the users would see: re-rendering a document that has not changed is slow, and serving a
 * document that has changed is wrong. The project arm gets its own case because it is the one a
 * reader is most likely to forget — the company sections of the PDF come from the project, not the
 * role.
 */
@ExtendWith(MockitoExtension.class)
class JobDescriptionServiceTest {

  private static final Integer POSITION_ID = 30;
  private static final Integer PROJECT_ID = 4001;
  private static final byte[] PDF = "%PDF-1.6 rendered".getBytes();

  @Mock private ProjectPositionRepository positionRepository;
  @Mock private JobDescriptionPdfGenerator pdfGenerator;
  @Mock private JobDescriptionStorageService storageService;

  @InjectMocks private JobDescriptionService jobDescriptionService;

  @Captor private ArgumentCaptor<OffsetDateTime> renderedAtCaptor;

  private ProjectEntity project;
  private ProjectPositionEntity position;

  @BeforeEach
  void setUp() {
    OffsetDateTime lastWeek = OffsetDateTime.now().minusDays(7);

    project = new ProjectEntity();
    project.setId(PROJECT_ID);
    project.setTitle("Nền tảng chia sẻ kiến thức");
    project.setUpdatedAt(lastWeek);

    position = new ProjectPositionEntity();
    position.setId(POSITION_ID);
    position.setProject(project);
    position.setTitle("Kỹ sư Backend");
    position.setRoleSummary("Chịu trách nhiệm phần API và các job chạy nền.");
    position.setRequiredSkills(List.of("Java", "Spring"));
    position.setUpdatedAt(lastWeek);
  }

  private void cachedAt(OffsetDateTime renderedAt) {
    position.setJdObjectKey("job-descriptions/4001/30-old.pdf");
    position.setJdRenderedAt(renderedAt);
  }

  @Test
  @DisplayName("should render, store and sign on the first request for a role")
  void shouldRenderOnFirstRequest() {
    // Given: nothing has ever been rendered for this role.
    when(positionRepository.findByIdWithProjectAuthor(POSITION_ID))
        .thenReturn(Optional.of(position));
    when(pdfGenerator.render(project, position)).thenReturn(PDF);
    when(storageService.upload(PROJECT_ID, POSITION_ID, PDF))
        .thenReturn("job-descriptions/new.pdf");
    when(storageService.signedUrl("job-descriptions/new.pdf")).thenReturn("https://minio/new.pdf");

    // When
    JobDescriptionUrlResponse response = jobDescriptionService.getOrRender(POSITION_ID);

    // Then
    assertThat(response.url()).isEqualTo("https://minio/new.pdf");
    assertThat(response.renderedAt()).isNotNull();
    verify(positionRepository)
        .markJobDescriptionRendered(
            eq(POSITION_ID), eq("job-descriptions/new.pdf"), renderedAtCaptor.capture());
    // The stored timestamp must be the one handed back, or the next request would disagree with
    // the client about how old the document is.
    assertThat(renderedAtCaptor.getValue()).isEqualTo(response.renderedAt());
    // Nothing to clean up on a first render.
    verify(storageService).deleteQuietly(null);
  }

  @Test
  @DisplayName("should re-use the stored PDF while nothing has been edited since")
  void shouldServeTheCachedRender() {
    // Given: rendered after the last edit to both the role and the project.
    cachedAt(OffsetDateTime.now().minusMinutes(5));
    when(positionRepository.findByIdWithProjectAuthor(POSITION_ID))
        .thenReturn(Optional.of(position));
    when(storageService.signedUrl(position.getJdObjectKey())).thenReturn("https://minio/old.pdf");

    // When
    JobDescriptionUrlResponse response = jobDescriptionService.getOrRender(POSITION_ID);

    // Then: the bytes only change when the JD does, so a reader costs nothing after the first.
    assertThat(response.url()).isEqualTo("https://minio/old.pdf");
    assertThat(response.renderedAt()).isEqualTo(position.getJdRenderedAt());
    verifyNoInteractions(pdfGenerator);
    verify(storageService, never()).upload(anyInt(), anyInt(), any());
    verify(positionRepository, never()).markJobDescriptionRendered(any(), any(), any());
  }

  @Test
  @DisplayName("should re-render once the role itself has been edited")
  void shouldRerenderAfterAPositionEdit() {
    // Given: the render is older than the last edit to the role.
    cachedAt(OffsetDateTime.now().minusHours(2));
    position.setUpdatedAt(OffsetDateTime.now().minusMinutes(1));
    when(positionRepository.findByIdWithProjectAuthor(POSITION_ID))
        .thenReturn(Optional.of(position));
    when(pdfGenerator.render(project, position)).thenReturn(PDF);
    when(storageService.upload(PROJECT_ID, POSITION_ID, PDF)).thenReturn("job-descriptions/v2.pdf");
    when(storageService.signedUrl("job-descriptions/v2.pdf")).thenReturn("https://minio/v2.pdf");

    // When
    JobDescriptionUrlResponse response = jobDescriptionService.getOrRender(POSITION_ID);

    // Then: and the superseded object goes, since nothing points at it any more.
    assertThat(response.url()).isEqualTo("https://minio/v2.pdf");
    verify(storageService).deleteQuietly("job-descriptions/4001/30-old.pdf");
  }

  @Test
  @DisplayName("should re-render when the project was edited, not just the role")
  void shouldRerenderAfterAProjectEdit() {
    // Given: the role is untouched, but the company overview and culture printed in the document
    // live on the project — the arm a staleness flag on the position would have missed.
    cachedAt(OffsetDateTime.now().minusHours(2));
    project.setUpdatedAt(OffsetDateTime.now().minusMinutes(1));
    when(positionRepository.findByIdWithProjectAuthor(POSITION_ID))
        .thenReturn(Optional.of(position));
    when(pdfGenerator.render(project, position)).thenReturn(PDF);
    when(storageService.upload(PROJECT_ID, POSITION_ID, PDF)).thenReturn("job-descriptions/v3.pdf");
    when(storageService.signedUrl("job-descriptions/v3.pdf")).thenReturn("https://minio/v3.pdf");

    // When
    JobDescriptionUrlResponse response = jobDescriptionService.getOrRender(POSITION_ID);

    // Then
    assertThat(response.url()).isEqualTo("https://minio/v3.pdf");
    verify(pdfGenerator).render(project, position);
  }

  @Test
  @DisplayName("should re-render when a key was stored but the timestamp was not")
  void shouldRerenderOnHalfWrittenCache() {
    // Given: a row that says it has a document but not when it was made — either half of the pair
    // missing means the cache cannot be trusted.
    position.setJdObjectKey("job-descriptions/4001/30-old.pdf");
    when(positionRepository.findByIdWithProjectAuthor(POSITION_ID))
        .thenReturn(Optional.of(position));
    when(pdfGenerator.render(project, position)).thenReturn(PDF);
    when(storageService.upload(PROJECT_ID, POSITION_ID, PDF)).thenReturn("job-descriptions/v4.pdf");
    when(storageService.signedUrl("job-descriptions/v4.pdf")).thenReturn("https://minio/v4.pdf");

    // When / Then
    assertThat(jobDescriptionService.getOrRender(POSITION_ID).url())
        .isEqualTo("https://minio/v4.pdf");
  }

  @Test
  @DisplayName("should 404 for a position that does not exist")
  void shouldThrowNotFound() {
    when(positionRepository.findByIdWithProjectAuthor(POSITION_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> jobDescriptionService.getOrRender(POSITION_ID))
        .isInstanceOf(NotFoundException.class);

    verifyNoInteractions(pdfGenerator, storageService);
  }
}
