package com.socialapp.matchmaking.service;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.matchmaking.dto.JobDescriptionUrlResponse;
import com.socialapp.matchmaking.entity.ProjectEntity;
import com.socialapp.matchmaking.entity.ProjectPositionEntity;
import com.socialapp.matchmaking.repository.ProjectPositionRepository;

import lombok.RequiredArgsConstructor;

/**
 * Serves one role's job description as a PDF, rendering it the first time anyone asks and re-using
 * that render until the JD changes.
 *
 * <p><b>Render on read, not on write.</b> Building the PDF inside {@code createProject} would put
 * a MinIO round trip and a font load on the request that creates a project — and would spend both
 * on the roles nobody ever opens, which is most of them on a board this size. Rendering here costs
 * the first reader one extra second and every reader after them nothing.
 *
 * <p><b>Staleness is a clock comparison, not a flag.</b> {@code jdRenderedAt} is checked against
 * {@code updatedAt} on the position <em>and</em> on its project, because the company sections of
 * the document come from the project. A flag set by the write path would have to be set in five
 * places and would be wrong the first time someone forgot one; a timestamp cannot be forgotten,
 * since {@code @UpdateTimestamp} moves on every write already.
 */
@Service
@RequiredArgsConstructor
public class JobDescriptionService {

  private final ProjectPositionRepository positionRepository;
  private final JobDescriptionPdfGenerator pdfGenerator;
  private final JobDescriptionStorageService storageService;

  /**
   * A signed URL to this role's JD, valid for a day.
   *
   * <p>Readable by any signed-in user, exactly like {@code ProjectQueryService.getProject}: a job
   * posting exists to be read, and a document only its author can open is not a posting.
   *
   * <p>Not {@code readOnly}: a cache miss writes the new object key back. The transaction is short
   * and the write is idempotent in effect — two readers racing a first render produce two objects
   * and store one key, leaving the loser's object orphaned in the bucket. That is the accepted
   * cost; the alternative is locking a row on a read path to save a few kilobytes.
   */
  @Transactional
  public JobDescriptionUrlResponse getOrRender(Integer positionId) {
    ProjectPositionEntity position =
        positionRepository
            .findByIdWithProjectAuthor(positionId)
            .orElseThrow(() -> new NotFoundException("Position not found with ID: " + positionId));
    ProjectEntity project = position.getProject();

    if (!isStale(position, project)) {
      return new JobDescriptionUrlResponse(
          storageService.signedUrl(position.getJdObjectKey()), position.getJdRenderedAt());
    }

    byte[] pdf = pdfGenerator.render(project, position);
    String newKey = storageService.upload(project.getId(), positionId, pdf);
    OffsetDateTime renderedAt = OffsetDateTime.now();

    // A bulk update rather than a dirty-checked save, and this is the whole trick: saving the
    // entity would fire @UpdateTimestamp, push updatedAt past the jdRenderedAt being written, and
    // leave the render permanently stale — every request re-rendering the same document forever.
    // It also leaves @Version alone, which is correct: the version guards the accept/quantity race
    // over seats, and caching a PDF is not a change to the role.
    positionRepository.markJobDescriptionRendered(positionId, newKey, renderedAt);
    storageService.deleteQuietly(position.getJdObjectKey());

    return new JobDescriptionUrlResponse(storageService.signedUrl(newKey), renderedAt);
  }

  /**
   * Whether the stored PDF no longer matches what the JD says. Never rendered, or rendered before
   * the last edit to either the role or the project it belongs to.
   */
  private boolean isStale(ProjectPositionEntity position, ProjectEntity project) {
    if (position.getJdObjectKey() == null || position.getJdRenderedAt() == null) {
      return true;
    }
    return isAfter(position.getUpdatedAt(), position.getJdRenderedAt())
        || isAfter(project.getUpdatedAt(), position.getJdRenderedAt());
  }

  private boolean isAfter(OffsetDateTime candidate, OffsetDateTime reference) {
    return candidate != null && candidate.isAfter(reference);
  }
}
