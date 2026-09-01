package com.socialapp.matchmaking.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.repository.UserProfessionalProfileRepository;
import com.socialapp.knowledge.service.ProfileMatchScorer;
import com.socialapp.matchmaking.dto.ProjectPositionResponseDto;
import com.socialapp.matchmaking.dto.ProjectResponseDto;
import com.socialapp.matchmaking.dto.SuggestedCandidateDto;
import com.socialapp.matchmaking.dto.SuggestedProjectDto;
import com.socialapp.matchmaking.entity.ProjectEntity;
import com.socialapp.matchmaking.entity.ProjectPositionEntity;
import com.socialapp.matchmaking.entity.enums.PositionStatus;
import com.socialapp.matchmaking.repository.ProjectApplicationRepository;
import com.socialapp.matchmaking.repository.ProjectPositionRepository;
import com.socialapp.matchmaking.repository.ProjectRepository;

import lombok.RequiredArgsConstructor;

/**
 * Matching people to work, in both directions: candidates for a role, and roles for a person.
 *
 * <p>Both sides share one shape — pull a bounded pool from the database ordered by the cheapest
 * useful signal, drop what must never be suggested, score what is left in memory, then trim to the
 * caller's limit. It is the shape {@code FriendshipService} already uses for friend suggestions,
 * and it exists because ranking is not expressible in the same query that has to stay indexed.
 *
 * <p>Scoring itself lives in {@link ProfileMatchScorer} so that this class and {@code
 * FriendshipService} cannot drift into two different answers to "how similar are these two
 * backgrounds", which is exactly what had happened.
 */
@Service
@RequiredArgsConstructor
public class MatchmakingService {
  private final UserProfessionalProfileRepository profileRepository;
  private final ProjectPositionRepository positionRepository;
  private final ProjectRepository projectRepository;
  private final ProjectApplicationRepository applicationRepository;
  private final BlockQueryService blockQueryService;

  /**
   * How many profiles are pulled before ranking and trimming to the caller's limit. Larger than
   * any sane {@code limit} so the in-memory pass has something to choose from, but bounded so a
   * position asking for a skill as common as {@code "Java"} cannot select most of the profile
   * table. The database has already ordered this pool by skill overlap, so the rows dropped by the
   * bound are the weakest matches, not arbitrary ones.
   */
  private static final int CANDIDATE_POOL_SIZE = 200;

  /**
   * The project equivalent, and bounded for the same reason. Unlike the candidate pool this one is
   * ordered by recency rather than by score — the score depends on a jsonb intersection across two
   * tables that SQL cannot rank cheaply — so the bound really does mean "the newest 200 open
   * projects". That is the right trade while a deployment holds hundreds of projects; if it ever
   * holds tens of thousands, the overlap has to move into the query.
   */
  private static final int PROJECT_POOL_SIZE = 200;

  /**
   * Weights for the two halves of a project's score. Skills outrank domains deliberately: matching
   * what a project needs <em>built</em> is a stronger signal than matching what it is about, and
   * keeping the multipliers apart means one shared domain can never outrank one shared skill.
   */
  private static final int SKILL_WEIGHT = 3;

  private static final int DOMAIN_WEIGHT = 2;

  /**
   * A same-role bonus small enough that it only ever breaks ties between candidates with equal
   * skill overlap — it must not let a {@code BACKEND} label outweigh an actual shared skill.
   */
  private static final int ROLE_BONUS = 2;

  /**
   * Who might fill this role, best match first, for the project owner to approach.
   *
   * <p><b>Owner-only.</b> The reply carries other users' job title, seniority, years of experience
   * and tech stack, which is a professional profile in all but name. This took only a position id
   * and answered any signed-in caller, so any of them could walk position ids and harvest the
   * directory. Its sibling {@code ProjectQueryService.getApplicationsForProject} already took the
   * caller and threw {@link ForbiddenException}; this now matches it.
   *
   * <p>Three exclusions, none of which the old version applied. The <b>owner themselves</b>, who
   * cannot apply to their own project and so was pure noise at the top of their own list — done in
   * SQL so the row does not consume a slot in the pool. <b>Anyone who has already applied</b>,
   * whatever the outcome: re-suggesting someone the owner rejected is worse than useless. And
   * <b>blocked users in either direction</b>, which is filtered here rather than in the query for
   * the reason {@code FriendshipService} records — the pool is a cached-shaped thing and blocking
   * must take effect immediately.
   *
   * <p>{@code @Transactional} because the ownership check walks two LAZY associations
   * ({@code position.project.author}) and {@code open-in-view} is off.
   */
  @Transactional(readOnly = true)
  public List<SuggestedCandidateDto> suggestCandidates(
      Integer positionId, Integer callerId, int limit) {
    ProjectPositionEntity position =
        positionRepository
            .findById(positionId)
            .orElseThrow(() -> new NotFoundException("Position not found"));

    if (!position.getProject().getAuthor().getId().equals(callerId)) {
      throw new ForbiddenException("Not authorized to view candidates for this position");
    }

    List<String> requiredSkills = position.getRequiredSkills();
    if (requiredSkills == null || requiredSkills.isEmpty()) {
      return List.of();
    }

    // Lowercased here because SQL lowers only its own side; see findCandidatesBySkills.
    List<String> lowered = requiredSkills.stream().map(String::toLowerCase).toList();
    List<UserProfessionalProfileEntity> pool =
        profileRepository.findCandidatesBySkills(lowered, callerId, CANDIDATE_POOL_SIZE);
    if (pool.isEmpty()) {
      return List.of();
    }

    Set<Integer> excluded =
        Set.copyOf(applicationRepository.findApplicantIdsByPositionId(positionId));
    Set<Integer> blocked = blockQueryService.blockedPairIds(callerId);

    // The owner's own profile, for the same-role tiebreak. Absent is fine and common — plenty of
    // people post a project without ever filling in a professional profile — and simply means
    // every candidate scores 0 on that half rather than that the endpoint stops working.
    UserProfessionalProfileEntity ownerProfile = profileRepository.findById(callerId).orElse(null);

    return pool.stream()
        .filter(p -> !excluded.contains(p.getUserId()))
        .filter(p -> !blocked.contains(p.getUserId()))
        .map(p -> toCandidateDto(p, requiredSkills, ownerProfile))
        .sorted(
            Comparator.comparingInt(SuggestedCandidateDto::getMatchScore)
                .reversed()
                // nullsLast wraps reverseOrder rather than the whole comparator being
                // .reversed(): reversing a nullsLast comparator moves the nulls to the FRONT,
                // which floated candidates who never stated their experience above everyone else.
                .thenComparing(
                    SuggestedCandidateDto::getYearsOfExperience,
                    Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(SuggestedCandidateDto::getUserId))
        .limit(limit)
        .toList();
  }

  /**
   * Projects worth this user's time, best match first — the direction that did not exist.
   *
   * <p>{@code GET /v1/api/projects} is ordered by id descending and nothing else, so the only way
   * to find a project that fits was to scroll until one appeared. This reads the caller's own
   * professional profile and scores every open project against it.
   *
   * <p><b>No profile means an empty list</b>, not an error and not the unranked project list. This
   * endpoint's whole contract is "ranked by how well it fits you"; with nothing to fit against,
   * returning the plain newest-first list under that name would be a claim the data cannot
   * support. Clients fall back to {@code GET /v1/api/projects}, which is the honest answer.
   *
   * <p>Projects scoring 0 are dropped for the same reason. A list padded with unrelated projects
   * is the ordinary browse list wearing a label it has not earned.
   *
   * <p><b>{@code primaryRole} does not participate here</b>, unlike in {@link #suggestCandidates}.
   * {@code ProjectPositionEntity} has no role field — only a free-text {@code title} — and
   * inferring {@code DATA_ML} from strings like "Data Scientist" works right up until someone
   * writes "ML Engineer (part-time)". Skills and domains are both structured; role is not.
   *
   * <p>Query count is flat regardless of pool size: profile, project pool, that pool's positions
   * in one {@code IN} lookup, the caller's applications, and blocks.
   */
  @Transactional(readOnly = true)
  public List<SuggestedProjectDto> suggestProjects(Integer callerId, int limit) {
    UserProfessionalProfileEntity profile = profileRepository.findById(callerId).orElse(null);
    if (profile == null) {
      return List.of();
    }

    List<ProjectEntity> pool =
        projectRepository.findOpenProjectsForMatching(
            callerId, PageRequest.of(0, PROJECT_POOL_SIZE));
    if (pool.isEmpty()) {
      return List.of();
    }

    Set<Integer> appliedProjectIds =
        Set.copyOf(applicationRepository.findProjectIdsByApplicantId(callerId));
    Set<Integer> blocked = blockQueryService.blockedPairIds(callerId);

    List<ProjectEntity> visible =
        pool.stream()
            .filter(p -> !appliedProjectIds.contains(p.getId()))
            .filter(p -> !blocked.contains(p.getAuthor().getId()))
            .toList();
    if (visible.isEmpty()) {
      return List.of();
    }

    Map<Integer, List<ProjectPositionEntity>> positionsByProject =
        loadPositions(visible.stream().map(ProjectEntity::getId).toList());

    return visible.stream()
        .map(
            project ->
                score(
                    project, positionsByProject.getOrDefault(project.getId(), List.of()), profile))
        .filter(dto -> dto.matchScore() > 0)
        .sorted(
            Comparator.comparingInt(SuggestedProjectDto::matchScore)
                .reversed()
                .thenComparing(
                    Comparator.comparing((SuggestedProjectDto d) -> d.project().getId())
                        .reversed()))
        .limit(limit)
        .toList();
  }

  /**
   * Scores one project against one profile and builds its response in the same pass.
   *
   * <p>Only {@code OPEN} positions contribute their skills: a filled role cannot be applied to, so
   * matching a user's stack against it would suggest a project on the strength of work already
   * taken. The response still carries every position, because the project detail a user sees
   * should not silently hide its filled roles.
   */
  private SuggestedProjectDto score(
      ProjectEntity project,
      List<ProjectPositionEntity> positions,
      UserProfessionalProfileEntity profile) {
    List<String> openSkills =
        positions.stream()
            .filter(p -> p.getStatus() == PositionStatus.OPEN)
            .map(ProjectPositionEntity::getRequiredSkills)
            .filter(skills -> skills != null)
            .flatMap(List::stream)
            .toList();

    List<String> matchedSkills =
        ProfileMatchScorer.matchedSkills(profile.getKnownTechStack(), openSkills);
    List<String> matchedDomains =
        ProfileMatchScorer.matchedSkills(profile.getInterestedDomains(), project.getTags());

    int matchScore = matchedSkills.size() * SKILL_WEIGHT + matchedDomains.size() * DOMAIN_WEIGHT;

    List<ProjectPositionResponseDto> positionDtos =
        positions.stream().map(ProjectPositionResponseDto::from).toList();

    return new SuggestedProjectDto(
        ProjectResponseDto.from(project, positionDtos), matchScore, matchedSkills, matchedDomains);
  }

  /**
   * Every position on the pooled projects in one query, grouped by project.
   *
   * <p>Same trick and same reason as {@code ProjectQueryService.loadPositions}: reading {@code
   * project.getPositions()} per row would be one query per project. Entities rather than DTOs here
   * because {@link #score} needs both the required skills and the response mapping out of them.
   */
  private Map<Integer, List<ProjectPositionEntity>> loadPositions(List<Integer> projectIds) {
    if (projectIds.isEmpty()) {
      return Map.of();
    }
    return positionRepository.findByProjectIdIn(projectIds).stream()
        .collect(Collectors.groupingBy(p -> p.getProject().getId()));
  }

  /**
   * {@code matchedSkills} is spelled the way the <em>position</em> spells it: the project owner
   * wrote those words, and they are reading this list, so their own vocabulary is what reads as an
   * answer to their posting.
   */
  private SuggestedCandidateDto toCandidateDto(
      UserProfessionalProfileEntity profile,
      List<String> requiredSkills,
      UserProfessionalProfileEntity ownerProfile) {
    List<String> matchedSkills =
        ProfileMatchScorer.matchedSkills(requiredSkills, profile.getKnownTechStack());

    boolean sameRole =
        ownerProfile != null
            && ProfileMatchScorer.sameRole(ownerProfile.getPrimaryRole(), profile.getPrimaryRole());

    return SuggestedCandidateDto.builder()
        .userId(profile.getUserId())
        .jobTitle(profile.getJobTitle())
        .seniorityLevel(profile.getSeniorityLevel())
        .yearsOfExperience(profile.getYearsOfExperience())
        .primaryRole(profile.getPrimaryRole())
        .knownTechStack(profile.getKnownTechStack())
        .matchScore(matchedSkills.size() * SKILL_WEIGHT + (sameRole ? ROLE_BONUS : 0))
        .matchedSkills(matchedSkills)
        .build();
  }
}
