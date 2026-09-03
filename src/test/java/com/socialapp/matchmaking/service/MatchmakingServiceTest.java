package com.socialapp.matchmaking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.entity.enums.PrimaryRole;
import com.socialapp.knowledge.entity.enums.SeniorityLevel;
import com.socialapp.knowledge.repository.UserProfessionalProfileRepository;
import com.socialapp.matchmaking.dto.SuggestedCandidateDto;
import com.socialapp.matchmaking.dto.SuggestedProjectDto;
import com.socialapp.matchmaking.entity.ProjectEntity;
import com.socialapp.matchmaking.entity.ProjectPositionEntity;
import com.socialapp.matchmaking.entity.enums.PositionStatus;
import com.socialapp.matchmaking.repository.ProjectApplicationRepository;
import com.socialapp.matchmaking.repository.ProjectPositionRepository;
import com.socialapp.matchmaking.repository.ProjectRepository;
import com.socialapp.security.entity.UserEntity;

/**
 * Component (unit) tests for {@link MatchmakingService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing; Section 4.2.1 equivalence partitioning + Section 4.3.2 branch testing over the
 * null / empty / non-empty partitions of both suggestion directions, and over each exclusion rule).
 *
 * <p>The ranking assertions are the point of this class. The exclusions — owner, existing
 * applicants, blocked users, already-applied projects — each guard a specific way the endpoint
 * previously suggested something it should not have, so each gets its own test rather than being
 * folded into one happy path.
 */
@ExtendWith(MockitoExtension.class)
class MatchmakingServiceTest {

  private static final Integer POSITION_ID = 1;
  private static final Integer OWNER_ID = 1;
  private static final Integer STRANGER_ID = 999;
  private static final int LIMIT = 10;

  @Mock private UserProfessionalProfileRepository profileRepository;
  @Mock private ProjectPositionRepository positionRepository;
  @Mock private ProjectRepository projectRepository;
  @Mock private ProjectApplicationRepository applicationRepository;
  @Mock private BlockQueryService blockQueryService;

  @InjectMocks private MatchmakingService matchmakingService;

  @Captor private ArgumentCaptor<List<String>> skillsCaptor;

  // ── Fixtures ────────────────────────────────────────────────────────────────────────────────

  /** A position on a project owned by {@code OWNER_ID} — the only caller allowed to read it. */
  private static ProjectPositionEntity position(List<String> requiredSkills) {
    return position(requiredSkills, OWNER_ID);
  }

  private static ProjectPositionEntity position(List<String> requiredSkills, Integer ownerId) {
    ProjectPositionEntity position = new ProjectPositionEntity();
    position.setId(POSITION_ID);
    position.setRequiredSkills(requiredSkills);

    UserEntity author = new UserEntity();
    author.setId(ownerId);
    ProjectEntity project = new ProjectEntity();
    project.setId(500);
    project.setAuthor(author);
    position.setProject(project);

    return position;
  }

  /** A role that states an experience bar — the gate {@code V105} made expressible. */
  private static ProjectPositionEntity positionRequiring(
      List<String> requiredSkills, Integer minYears) {
    ProjectPositionEntity position = position(requiredSkills);
    position.setMinYearsExperience(minYears);
    return position;
  }

  private static UserProfessionalProfileEntity profile(Integer userId, String... techStack) {
    UserProfessionalProfileEntity profile = new UserProfessionalProfileEntity();
    profile.setUserId(userId);
    profile.setKnownTechStack(List.of(techStack));
    return profile;
  }

  private static ProjectEntity project(Integer id, Integer authorId, String... tags) {
    UserEntity author = new UserEntity();
    author.setId(authorId);

    ProjectEntity project = new ProjectEntity();
    project.setId(id);
    project.setTitle("Project " + id);
    project.setAuthor(author);
    project.setTags(List.of(tags));
    return project;
  }

  private static ProjectPositionEntity openPositionOn(
      ProjectEntity project, Integer id, String... requiredSkills) {
    return positionOn(project, id, PositionStatus.OPEN, requiredSkills);
  }

  private static ProjectPositionEntity positionOn(
      ProjectEntity project, Integer id, PositionStatus status, String... requiredSkills) {
    ProjectPositionEntity position = new ProjectPositionEntity();
    position.setId(id);
    position.setProject(project);
    position.setStatus(status);
    position.setRequiredSkills(List.of(requiredSkills));
    return position;
  }

  // ── suggestCandidates ───────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("suggestCandidates")
  class SuggestCandidatesTests {

    @Test
    @DisplayName("should reject when the position does not exist")
    void shouldThrowNotFoundException_whenPositionDoesNotExist() {
      // Given
      when(positionRepository.findById(POSITION_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, LIMIT))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should refuse a caller who does not own the project")
    void shouldThrowForbiddenException_whenCallerIsNotTheProjectOwner() {
      // Given: a position on somebody else's project. The reply carries other users' job title,
      // seniority, years of experience and tech stack, so this is a directory harvest if left
      // open — the sibling endpoint getApplicationsForProject has always checked ownership.
      when(positionRepository.findById(POSITION_ID))
          .thenReturn(Optional.of(position(List.of("Java"), OWNER_ID)));

      // When / Then
      assertThatThrownBy(
              () -> matchmakingService.suggestCandidates(POSITION_ID, STRANGER_ID, LIMIT))
          .isInstanceOf(ForbiddenException.class);
      verifyNoInteractions(profileRepository);
    }

    @Test
    @DisplayName("should return no candidates when the position has no required skills list")
    void shouldReturnEmptyList_whenRequiredSkillsIsNull() {
      // Given
      when(positionRepository.findById(POSITION_ID)).thenReturn(Optional.of(position(null)));

      // When
      List<SuggestedCandidateDto> result =
          matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, LIMIT);

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should return no candidates when the required skills list is empty")
    void shouldReturnEmptyList_whenRequiredSkillsIsEmpty() {
      // Given
      when(positionRepository.findById(POSITION_ID)).thenReturn(Optional.of(position(List.of())));

      // When
      List<SuggestedCandidateDto> result =
          matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, LIMIT);

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should skip the exclusion lookups entirely when the candidate pool is empty")
    void shouldReturnEmptyList_whenPoolIsEmpty() {
      // Given: nobody in the directory knows any of the required skills.
      when(positionRepository.findById(POSITION_ID))
          .thenReturn(Optional.of(position(List.of("COBOL"))));
      when(profileRepository.findCandidatesBySkills(anyList(), eq(OWNER_ID), anyInt()))
          .thenReturn(List.of());

      // When
      List<SuggestedCandidateDto> result =
          matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, LIMIT);

      // Then: no point asking who already applied or who is blocked when there is nobody to filter.
      assertThat(result).isEmpty();
      verifyNoInteractions(applicationRepository, blockQueryService);
    }

    @Test
    @DisplayName("should lowercase the required skills before querying, so casing cannot miss")
    void shouldLowercaseRequiredSkills_beforeQuerying() {
      // Given: the owner typed the skills with their own capitalisation.
      when(positionRepository.findById(POSITION_ID))
          .thenReturn(Optional.of(position(List.of("Java", "Spring Boot"))));
      when(profileRepository.findCandidatesBySkills(skillsCaptor.capture(), eq(OWNER_ID), anyInt()))
          .thenReturn(List.of());

      // When
      matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, LIMIT);

      // Then: SQL lowers only its own side, so this side has to arrive already lowered.
      assertThat(skillsCaptor.getValue()).containsExactly("java", "spring boot");
    }

    @Test
    @DisplayName("should rank candidates by how many required skills they actually match")
    void shouldRankBySkillOverlap() {
      // Given: three candidates matching 1, 3 and 2 of the required skills respectively.
      when(positionRepository.findById(POSITION_ID))
          .thenReturn(Optional.of(position(List.of("Java", "Spring", "Redis"))));
      when(profileRepository.findCandidatesBySkills(anyList(), eq(OWNER_ID), anyInt()))
          .thenReturn(
              List.of(
                  profile(11, "Java"),
                  profile(22, "Java", "Spring", "Redis"),
                  profile(33, "Java", "Spring")));
      when(applicationRepository.findApplicantIdsByPositionId(POSITION_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.empty());

      // When
      List<SuggestedCandidateDto> result =
          matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, LIMIT);

      // Then: best fit first, which the old boolean filter could not express at all — and 11,
      // who matches one skill of three, is no longer in the list at all. One shared word out of
      // three is how a "Java, Spring, Redis" role used to end up recommending someone whose only
      // overlap was Java (PositionFit.MIN_SKILL_COVERAGE).
      assertThat(result).extracting(SuggestedCandidateDto::getUserId).containsExactly(22, 33);
      // 22 covers all three (9 + the full-coverage bonus), 33 covers two.
      assertThat(result).extracting(SuggestedCandidateDto::getMatchScore).containsExactly(12, 6);
      assertThat(result)
          .extracting(SuggestedCandidateDto::getSkillCoveragePercent)
          .containsExactly(100, 67);
    }

    @Test
    @DisplayName("should match skills case-insensitively when scoring")
    void shouldMatchSkillsCaseInsensitively() {
      // Given: the candidate wrote "java", the owner wrote "Java".
      when(positionRepository.findById(POSITION_ID))
          .thenReturn(Optional.of(position(List.of("Java", "React"))));
      when(profileRepository.findCandidatesBySkills(anyList(), eq(OWNER_ID), anyInt()))
          .thenReturn(List.of(profile(11, "java", "REACT")));
      when(applicationRepository.findApplicantIdsByPositionId(POSITION_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.empty());

      // When
      List<SuggestedCandidateDto> result =
          matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, LIMIT);

      // Then: both count (6), plus the full-coverage bonus for matching every skill asked for,
      // and the reply echoes the owner's own spelling back at them.
      assertThat(result)
          .singleElement()
          .satisfies(dto -> assertThat(dto.getMatchScore()).isEqualTo(9));
      assertThat(result.get(0).getMatchedSkills()).containsExactly("Java", "React");
    }

    @Test
    @DisplayName("should break a tie on skill overlap with a same-role bonus")
    void shouldPreferSameRole_whenSkillOverlapTies() {
      // Given: two equally-skilled candidates, one sharing the owner's specialisation.
      when(positionRepository.findById(POSITION_ID))
          .thenReturn(Optional.of(position(List.of("Java"))));

      UserProfessionalProfileEntity sameRole = profile(11, "Java");
      sameRole.setPrimaryRole(PrimaryRole.BACKEND);
      UserProfessionalProfileEntity otherRole = profile(22, "Java");
      otherRole.setPrimaryRole(PrimaryRole.FRONTEND);

      when(profileRepository.findCandidatesBySkills(anyList(), eq(OWNER_ID), anyInt()))
          .thenReturn(List.of(otherRole, sameRole));
      when(applicationRepository.findApplicantIdsByPositionId(POSITION_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());

      UserProfessionalProfileEntity ownerProfile = profile(OWNER_ID, "Java");
      ownerProfile.setPrimaryRole(PrimaryRole.BACKEND);
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.of(ownerProfile));

      // When
      List<SuggestedCandidateDto> result =
          matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, LIMIT);

      // Then
      assertThat(result).extracting(SuggestedCandidateDto::getUserId).containsExactly(11, 22);
      // 3 for the skill + 3 for covering the whole (one-skill) role, and +2 to the same-role one.
      assertThat(result).extracting(SuggestedCandidateDto::getMatchScore).containsExactly(8, 6);
    }

    @Test
    @DisplayName("should never let the role bonus outweigh an actual shared skill")
    void shouldRankSkillOverlapAboveRoleMatch() {
      // Given: one candidate shares the owner's role, the other shares one more skill.
      when(positionRepository.findById(POSITION_ID))
          .thenReturn(Optional.of(position(List.of("Java", "Spring"))));

      UserProfessionalProfileEntity roleMatch = profile(11, "Java");
      roleMatch.setPrimaryRole(PrimaryRole.BACKEND);
      UserProfessionalProfileEntity skillMatch = profile(22, "Java", "Spring");
      skillMatch.setPrimaryRole(PrimaryRole.FRONTEND);

      when(profileRepository.findCandidatesBySkills(anyList(), eq(OWNER_ID), anyInt()))
          .thenReturn(List.of(roleMatch, skillMatch));
      when(applicationRepository.findApplicantIdsByPositionId(POSITION_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());

      UserProfessionalProfileEntity ownerProfile = profile(OWNER_ID, "Java");
      ownerProfile.setPrimaryRole(PrimaryRole.BACKEND);
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.of(ownerProfile));

      // When
      List<SuggestedCandidateDto> result =
          matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, LIMIT);

      // Then: a label loses to a demonstrated skill.
      assertThat(result).extracting(SuggestedCandidateDto::getUserId).containsExactly(22, 11);
    }

    @Test
    @DisplayName("should drop a candidate who matches too few of the required skills")
    void shouldDropCandidatesBelowTheCoverageBar() {
      // Given: a four-skill role, and someone who shares one of them.
      when(positionRepository.findById(POSITION_ID))
          .thenReturn(
              Optional.of(position(List.of("Go", "Kubernetes", "Terraform", "PostgreSQL"))));
      when(profileRepository.findCandidatesBySkills(anyList(), eq(OWNER_ID), anyInt()))
          .thenReturn(List.of(profile(11, "PostgreSQL"), profile(22, "Go", "Kubernetes")));
      when(applicationRepository.findApplicantIdsByPositionId(POSITION_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.empty());

      // When
      List<SuggestedCandidateDto> result =
          matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, LIMIT);

      // Then: the SQL pool selects on "shares at least one skill" because that is what an index
      // can do; the shortlist is not obliged to repeat the compromise.
      assertThat(result).extracting(SuggestedCandidateDto::getUserId).containsExactly(22);
    }

    @Test
    @DisplayName("should drop a candidate below the experience bar the role states")
    void shouldDropCandidatesBelowTheExperienceBar() {
      // Given: the role asks for five years. Both candidates know the skill.
      when(positionRepository.findById(POSITION_ID))
          .thenReturn(Optional.of(positionRequiring(List.of("Java"), 5)));

      UserProfessionalProfileEntity tooJunior = profile(11, "Java");
      tooJunior.setYearsOfExperience(1);
      UserProfessionalProfileEntity qualified = profile(22, "Java");
      qualified.setYearsOfExperience(6);

      when(profileRepository.findCandidatesBySkills(anyList(), eq(OWNER_ID), anyInt()))
          .thenReturn(List.of(tooJunior, qualified));
      when(applicationRepository.findApplicantIdsByPositionId(POSITION_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.empty());

      // When
      List<SuggestedCandidateDto> result =
          matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, LIMIT);

      // Then: below the bar is a wrong match, not a weak one — no score can buy past it.
      assertThat(result).extracting(SuggestedCandidateDto::getUserId).containsExactly(22);
    }

    @Test
    @DisplayName("should drop a candidate who never stated their experience, once a bar is set")
    void shouldDropCandidatesWithUnknownExperience_whenTheRoleAsks() {
      // Given
      when(positionRepository.findById(POSITION_ID))
          .thenReturn(Optional.of(positionRequiring(List.of("Java"), 3)));
      when(profileRepository.findCandidatesBySkills(anyList(), eq(OWNER_ID), anyInt()))
          .thenReturn(List.of(profile(11, "Java")));
      when(applicationRepository.findApplicantIdsByPositionId(POSITION_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.empty());

      // When
      List<SuggestedCandidateDto> result =
          matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, LIMIT);

      // Then: a role that did not ask lets everyone through; a person who did not answer cannot
      // be shown to clear a bar that was asked for. The asymmetry is the point.
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should accept a candidate more senior than the role asks for")
    void shouldAcceptCandidatesAboveTheSeniorityBar() {
      // Given: the role asks for SENIOR.
      ProjectPositionEntity position = position(List.of("Java"));
      position.setSeniorityLevel(SeniorityLevel.SENIOR);
      when(positionRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

      UserProfessionalProfileEntity mid = profile(11, "Java");
      mid.setSeniorityLevel(SeniorityLevel.MID);
      UserProfessionalProfileEntity lead = profile(22, "Java");
      lead.setSeniorityLevel(SeniorityLevel.LEAD);

      when(profileRepository.findCandidatesBySkills(anyList(), eq(OWNER_ID), anyInt()))
          .thenReturn(List.of(mid, lead));
      when(applicationRepository.findApplicantIdsByPositionId(POSITION_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.empty());

      // When
      List<SuggestedCandidateDto> result =
          matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, LIMIT);

      // Then: a bar is a floor, not an equality check — a LEAD passes a SENIOR role.
      assertThat(result).extracting(SuggestedCandidateDto::getUserId).containsExactly(22);
    }

    @Test
    @DisplayName("should break a fully tied score with more years of experience first")
    void shouldPreferMoreExperience_whenScoresTie() {
      // Given
      when(positionRepository.findById(POSITION_ID))
          .thenReturn(Optional.of(position(List.of("Java"))));

      UserProfessionalProfileEntity junior = profile(11, "Java");
      junior.setYearsOfExperience(2);
      UserProfessionalProfileEntity senior = profile(22, "Java");
      senior.setYearsOfExperience(9);
      UserProfessionalProfileEntity unknown = profile(33, "Java");

      when(profileRepository.findCandidatesBySkills(anyList(), eq(OWNER_ID), anyInt()))
          .thenReturn(List.of(junior, unknown, senior));
      when(applicationRepository.findApplicantIdsByPositionId(POSITION_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.empty());

      // When
      List<SuggestedCandidateDto> result =
          matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, LIMIT);

      // Then: an unstated experience sorts last rather than blowing up on the null.
      assertThat(result).extracting(SuggestedCandidateDto::getUserId).containsExactly(22, 11, 33);
    }

    @Test
    @DisplayName("should exclude anyone who has already applied to this position")
    void shouldExcludeExistingApplicants() {
      // Given: 22 already applied — whether it was accepted, rejected or is still pending, the
      // owner has seen them and does not need them suggested again.
      when(positionRepository.findById(POSITION_ID))
          .thenReturn(Optional.of(position(List.of("Java"))));
      when(profileRepository.findCandidatesBySkills(anyList(), eq(OWNER_ID), anyInt()))
          .thenReturn(List.of(profile(11, "Java"), profile(22, "Java")));
      when(applicationRepository.findApplicantIdsByPositionId(POSITION_ID)).thenReturn(List.of(22));
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.empty());

      // When
      List<SuggestedCandidateDto> result =
          matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, LIMIT);

      // Then
      assertThat(result).extracting(SuggestedCandidateDto::getUserId).containsExactly(11);
    }

    @Test
    @DisplayName("should exclude users blocked in either direction")
    void shouldExcludeBlockedUsers() {
      // Given
      when(positionRepository.findById(POSITION_ID))
          .thenReturn(Optional.of(position(List.of("Java"))));
      when(profileRepository.findCandidatesBySkills(anyList(), eq(OWNER_ID), anyInt()))
          .thenReturn(List.of(profile(11, "Java"), profile(22, "Java")));
      when(applicationRepository.findApplicantIdsByPositionId(POSITION_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of(11));
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.empty());

      // When
      List<SuggestedCandidateDto> result =
          matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, LIMIT);

      // Then
      assertThat(result).extracting(SuggestedCandidateDto::getUserId).containsExactly(22);
    }

    @Test
    @DisplayName("should return at most the requested number of candidates")
    void shouldRespectTheLimit() {
      // Given
      when(positionRepository.findById(POSITION_ID))
          .thenReturn(Optional.of(position(List.of("Java"))));
      when(profileRepository.findCandidatesBySkills(anyList(), eq(OWNER_ID), anyInt()))
          .thenReturn(List.of(profile(11, "Java"), profile(22, "Java"), profile(33, "Java")));
      when(applicationRepository.findApplicantIdsByPositionId(POSITION_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.empty());

      // When
      List<SuggestedCandidateDto> result =
          matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, 2);

      // Then
      assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("should carry the profile fields a project owner needs to judge fit")
    void shouldMapTheCandidateProfile() {
      // Given
      when(positionRepository.findById(POSITION_ID))
          .thenReturn(Optional.of(position(List.of("Java"))));
      UserProfessionalProfileEntity candidate = profile(77, "Java", "Kafka");
      candidate.setJobTitle("Backend Engineer");
      candidate.setPrimaryRole(PrimaryRole.BACKEND);
      candidate.setYearsOfExperience(6);
      when(profileRepository.findCandidatesBySkills(anyList(), eq(OWNER_ID), anyInt()))
          .thenReturn(List.of(candidate));
      when(applicationRepository.findApplicantIdsByPositionId(POSITION_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.empty());

      // When
      List<SuggestedCandidateDto> result =
          matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, LIMIT);

      // Then
      assertThat(result)
          .singleElement()
          .satisfies(
              dto -> {
                assertThat(dto.getUserId()).isEqualTo(77);
                assertThat(dto.getJobTitle()).isEqualTo("Backend Engineer");
                assertThat(dto.getPrimaryRole()).isEqualTo(PrimaryRole.BACKEND);
                assertThat(dto.getYearsOfExperience()).isEqualTo(6);
                assertThat(dto.getKnownTechStack()).containsExactly("Java", "Kafka");
                assertThat(dto.getMatchedSkills()).containsExactly("Java");
              });
    }
  }

  // ── suggestProjects ─────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("suggestProjects")
  class SuggestProjectsTests {

    @Test
    @DisplayName("should return nothing when the caller has no professional profile")
    void shouldReturnEmptyList_whenCallerHasNoProfile() {
      // Given: there is nothing to rank against.
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.empty());

      // When
      List<SuggestedProjectDto> result = matchmakingService.suggestProjects(OWNER_ID, LIMIT);

      // Then: an empty list, not the plain newest-first project list wearing a "for you" label.
      assertThat(result).isEmpty();
      verifyNoInteractions(projectRepository, positionRepository);
    }

    @Test
    @DisplayName("should return nothing when no open project is available to the caller")
    void shouldReturnEmptyList_whenPoolIsEmpty() {
      // Given
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.of(profile(OWNER_ID, "Java")));
      when(projectRepository.findOpenProjectsForMatching(eq(OWNER_ID), any(Pageable.class)))
          .thenReturn(List.of());

      // When
      List<SuggestedProjectDto> result = matchmakingService.suggestProjects(OWNER_ID, LIMIT);

      // Then
      assertThat(result).isEmpty();
      verifyNoInteractions(positionRepository);
    }

    @Test
    @DisplayName("should score a project on both shared skills and shared domains")
    void shouldScoreSkillsAndDomains() {
      // Given: one shared skill (weight 3) and one shared domain (weight 2).
      UserProfessionalProfileEntity caller = profile(OWNER_ID, "Java", "Kafka");
      caller.setInterestedDomains(List.of("API Design", "Growth"));
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.of(caller));

      ProjectEntity project = project(4001, 900, "API Design", "Design Systems");
      when(projectRepository.findOpenProjectsForMatching(eq(OWNER_ID), any(Pageable.class)))
          .thenReturn(List.of(project));
      when(applicationRepository.findProjectIdsByApplicantId(OWNER_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());
      when(positionRepository.findByProjectIdIn(List.of(4001)))
          .thenReturn(List.of(openPositionOn(project, 4101, "Java", "PostgreSQL")));

      // When
      List<SuggestedProjectDto> result = matchmakingService.suggestProjects(OWNER_ID, LIMIT);

      // Then
      assertThat(result)
          .singleElement()
          .satisfies(
              dto -> {
                assertThat(dto.matchScore()).isEqualTo(5);
                assertThat(dto.matchedSkills()).containsExactly("Java");
                assertThat(dto.matchedDomains()).containsExactly("API Design");
                assertThat(dto.project().getId()).isEqualTo(4001);
                assertThat(dto.project().getPositions()).hasSize(1);
              });
    }

    @Test
    @DisplayName("should ignore the skills of positions that are no longer open")
    void shouldOnlyScoreOpenPositions() {
      // Given: the only position matching the caller's stack is already filled, so there is
      // nothing left on this project to apply to with those skills.
      UserProfessionalProfileEntity caller = profile(OWNER_ID, "Java");
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.of(caller));

      ProjectEntity project = project(4001, 900);
      when(projectRepository.findOpenProjectsForMatching(eq(OWNER_ID), any(Pageable.class)))
          .thenReturn(List.of(project));
      when(applicationRepository.findProjectIdsByApplicantId(OWNER_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());
      when(positionRepository.findByProjectIdIn(List.of(4001)))
          .thenReturn(
              List.of(
                  positionOn(project, 4101, PositionStatus.FILLED, "Java"),
                  openPositionOn(project, 4102, "React")));

      // When
      List<SuggestedProjectDto> result = matchmakingService.suggestProjects(OWNER_ID, LIMIT);

      // Then: score 0, so it is dropped entirely.
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should drop projects that match nothing at all")
    void shouldDropZeroScoreProjects() {
      // Given: two projects, only one of which has anything to do with the caller.
      UserProfessionalProfileEntity caller = profile(OWNER_ID, "Java");
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.of(caller));

      ProjectEntity matching = project(4001, 900);
      ProjectEntity unrelated = project(4002, 901);
      when(projectRepository.findOpenProjectsForMatching(eq(OWNER_ID), any(Pageable.class)))
          .thenReturn(List.of(unrelated, matching));
      when(applicationRepository.findProjectIdsByApplicantId(OWNER_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());
      when(positionRepository.findByProjectIdIn(List.of(4002, 4001)))
          .thenReturn(
              List.of(
                  openPositionOn(unrelated, 4201, "Figma"),
                  openPositionOn(matching, 4101, "Java")));

      // When
      List<SuggestedProjectDto> result = matchmakingService.suggestProjects(OWNER_ID, LIMIT);

      // Then: padding the list with unrelated projects would make "suggested" a lie.
      assertThat(result).extracting(dto -> dto.project().getId()).containsExactly(4001);
    }

    @Test
    @DisplayName("should exclude projects the caller has already applied to")
    void shouldExcludeAlreadyAppliedProjects() {
      // Given
      UserProfessionalProfileEntity caller = profile(OWNER_ID, "Java");
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.of(caller));

      ProjectEntity applied = project(4001, 900);
      ProjectEntity fresh = project(4002, 901);
      when(projectRepository.findOpenProjectsForMatching(eq(OWNER_ID), any(Pageable.class)))
          .thenReturn(List.of(applied, fresh));
      when(applicationRepository.findProjectIdsByApplicantId(OWNER_ID)).thenReturn(List.of(4001));
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());
      when(positionRepository.findByProjectIdIn(List.of(4002)))
          .thenReturn(List.of(openPositionOn(fresh, 4201, "Java")));

      // When
      List<SuggestedProjectDto> result = matchmakingService.suggestProjects(OWNER_ID, LIMIT);

      // Then
      assertThat(result).extracting(dto -> dto.project().getId()).containsExactly(4002);
    }

    @Test
    @DisplayName("should exclude projects owned by a user blocked in either direction")
    void shouldExcludeProjectsOfBlockedAuthors() {
      // Given
      UserProfessionalProfileEntity caller = profile(OWNER_ID, "Java");
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.of(caller));

      ProjectEntity blockedAuthors = project(4001, 900);
      ProjectEntity visible = project(4002, 901);
      when(projectRepository.findOpenProjectsForMatching(eq(OWNER_ID), any(Pageable.class)))
          .thenReturn(List.of(blockedAuthors, visible));
      when(applicationRepository.findProjectIdsByApplicantId(OWNER_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of(900));
      when(positionRepository.findByProjectIdIn(List.of(4002)))
          .thenReturn(List.of(openPositionOn(visible, 4201, "Java")));

      // When
      List<SuggestedProjectDto> result = matchmakingService.suggestProjects(OWNER_ID, LIMIT);

      // Then
      assertThat(result).extracting(dto -> dto.project().getId()).containsExactly(4002);
    }

    @Test
    @DisplayName("should return nothing, without loading positions, when every project is excluded")
    void shouldReturnEmptyList_whenEverythingIsFilteredOut() {
      // Given
      UserProfessionalProfileEntity caller = profile(OWNER_ID, "Java");
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.of(caller));
      when(projectRepository.findOpenProjectsForMatching(eq(OWNER_ID), any(Pageable.class)))
          .thenReturn(List.of(project(4001, 900)));
      when(applicationRepository.findProjectIdsByApplicantId(OWNER_ID)).thenReturn(List.of(4001));
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());

      // When
      List<SuggestedProjectDto> result = matchmakingService.suggestProjects(OWNER_ID, LIMIT);

      // Then
      assertThat(result).isEmpty();
      verifyNoInteractions(positionRepository);
    }

    @Test
    @DisplayName("should rank by score first and by recency only to break a tie")
    void shouldRankByScoreThenRecency() {
      // Given: 4001 matches two skills, 4002 and 4003 one each.
      UserProfessionalProfileEntity caller = profile(OWNER_ID, "Java", "Kafka");
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.of(caller));

      ProjectEntity strong = project(4001, 900);
      ProjectEntity olderWeak = project(4002, 901);
      ProjectEntity newerWeak = project(4003, 902);
      when(projectRepository.findOpenProjectsForMatching(eq(OWNER_ID), any(Pageable.class)))
          .thenReturn(List.of(newerWeak, olderWeak, strong));
      when(applicationRepository.findProjectIdsByApplicantId(OWNER_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());
      when(positionRepository.findByProjectIdIn(List.of(4003, 4002, 4001)))
          .thenReturn(
              List.of(
                  openPositionOn(newerWeak, 4301, "Java"),
                  openPositionOn(olderWeak, 4201, "Java"),
                  openPositionOn(strong, 4101, "Java", "Kafka")));

      // When
      List<SuggestedProjectDto> result = matchmakingService.suggestProjects(OWNER_ID, LIMIT);

      // Then: the strong match leads; the two equal ones fall back to newest first. Every role
      // here is fully covered by the caller, so each carries the full-coverage bonus too.
      assertThat(result).extracting(dto -> dto.project().getId()).containsExactly(4001, 4003, 4002);
      assertThat(result).extracting(SuggestedProjectDto::matchScore).containsExactly(9, 6, 6);
      assertThat(result.get(0).qualifiedPositionIds()).containsExactly(4101);
    }

    @Test
    @DisplayName("should not suggest a project on shared domains alone")
    void shouldNotSuggestOnDomainsAlone() {
      // Given: the caller cares about the same things this project is about, but the only open
      // role wants a stack they do not have.
      UserProfessionalProfileEntity caller = profile(OWNER_ID, "Java");
      caller.setInterestedDomains(List.of("API Design"));
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.of(caller));

      ProjectEntity project = project(4001, 900, "API Design");
      when(projectRepository.findOpenProjectsForMatching(eq(OWNER_ID), any(Pageable.class)))
          .thenReturn(List.of(project));
      when(applicationRepository.findProjectIdsByApplicantId(OWNER_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());
      when(positionRepository.findByProjectIdIn(List.of(4001)))
          .thenReturn(List.of(openPositionOn(project, 4101, "Figma", "Sketch")));

      // When
      List<SuggestedProjectDto> result = matchmakingService.suggestProjects(OWNER_ID, LIMIT);

      // Then: shared interests describe a project worth reading about, not one to apply to.
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should not suggest a project whose only open role is above the caller")
    void shouldRespectTheExperienceBar() {
      // Given
      UserProfessionalProfileEntity caller = profile(OWNER_ID, "Java");
      caller.setYearsOfExperience(1);
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.of(caller));

      ProjectEntity project = project(4001, 900);
      when(projectRepository.findOpenProjectsForMatching(eq(OWNER_ID), any(Pageable.class)))
          .thenReturn(List.of(project));
      when(applicationRepository.findProjectIdsByApplicantId(OWNER_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());

      ProjectPositionEntity seniorRole = openPositionOn(project, 4101, "Java");
      seniorRole.setMinYearsExperience(5);
      when(positionRepository.findByProjectIdIn(List.of(4001))).thenReturn(List.of(seniorRole));

      // When
      List<SuggestedProjectDto> result = matchmakingService.suggestProjects(OWNER_ID, LIMIT);

      // Then: suggesting a role someone cannot take wastes the applicant's time and the owner's.
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName(
        "should score a project by its best role and name every role the caller qualifies for")
    void shouldScoreByBestRoleAndNameThem() {
      // Given: three open roles — one the caller fully covers, one they half cover, one they
      // cannot take at all.
      UserProfessionalProfileEntity caller = profile(OWNER_ID, "Java", "Spring");
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.of(caller));

      ProjectEntity project = project(4001, 900);
      when(projectRepository.findOpenProjectsForMatching(eq(OWNER_ID), any(Pageable.class)))
          .thenReturn(List.of(project));
      when(applicationRepository.findProjectIdsByApplicantId(OWNER_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());
      when(positionRepository.findByProjectIdIn(List.of(4001)))
          .thenReturn(
              List.of(
                  openPositionOn(project, 4101, "Java", "Spring"),
                  openPositionOn(project, 4102, "Java", "Kafka"),
                  openPositionOn(project, 4103, "Figma")));

      // When
      List<SuggestedProjectDto> result = matchmakingService.suggestProjects(OWNER_ID, LIMIT);

      // Then: the project inherits the best role it can offer (2 skills + full coverage = 9),
      // rather than the sum of scraps across roles nobody could apply to as a whole.
      assertThat(result)
          .singleElement()
          .satisfies(
              dto -> {
                assertThat(dto.matchScore()).isEqualTo(9);
                assertThat(dto.qualifiedPositionIds()).containsExactly(4101, 4102);
                assertThat(dto.matchedSkills()).containsExactly("Java", "Spring");
              });
    }

    @Test
    @DisplayName("should return at most the requested number of projects")
    void shouldRespectTheLimit() {
      // Given
      UserProfessionalProfileEntity caller = profile(OWNER_ID, "Java");
      when(profileRepository.findById(OWNER_ID)).thenReturn(Optional.of(caller));

      ProjectEntity first = project(4001, 900);
      ProjectEntity second = project(4002, 901);
      when(projectRepository.findOpenProjectsForMatching(eq(OWNER_ID), any(Pageable.class)))
          .thenReturn(List.of(first, second));
      when(applicationRepository.findProjectIdsByApplicantId(OWNER_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(OWNER_ID)).thenReturn(Set.of());
      when(positionRepository.findByProjectIdIn(List.of(4001, 4002)))
          .thenReturn(
              List.of(openPositionOn(first, 4101, "Java"), openPositionOn(second, 4201, "Java")));

      // When
      List<SuggestedProjectDto> result = matchmakingService.suggestProjects(OWNER_ID, 1);

      // Then
      assertThat(result).hasSize(1);
    }
  }
}
