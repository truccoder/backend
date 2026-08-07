package com.socialapp.roadmap.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.roadmap.dto.RoadmapProgressDto;
import com.socialapp.roadmap.service.SkillVerificationService;
import com.socialapp.security.util.SecurityUtils;

import lombok.RequiredArgsConstructor;

/**
 * The "verified skills" card on a profile. The repository query behind it
 * ({@code UserRoadmapProgressRepository.findByUserId}) has existed since the roadmap feature
 * shipped; nothing ever exposed it, so the card had no data source.
 *
 * <p><b>Keyed by id, not by handle</b>, matching {@code /users/{userId}/posts} and
 * {@code /users/{userId}/reputation}. Only {@code /users/{username}/profile} takes a handle: the
 * client resolves handle → id there once, then uses the id for every section of the page. Two
 * lookup keys for the same resource means every section has to know which one it takes.
 *
 * <p>Open to signed-out visitors, because the profile it belongs to is. That is why
 * {@code getCurrentUserIdOrNull()} is used rather than the throwing variant — the throwing one
 * would 401 a guest regardless of what {@code SecurityConfig} permits — and it is why
 * {@code RoadmapProgressDto} carries no proof links and no verifier. The viewer id still matters:
 * the owner sees pending and rejected rows, nobody else does.
 */
@RestController
@RequestMapping("/v1/api/users/{userId}/roadmap-progress")
@RequiredArgsConstructor
public class UserRoadmapProgressController {

  private final SkillVerificationService skillVerificationService;

  @GetMapping
  public List<RoadmapProgressDto> getRoadmapProgress(@PathVariable Integer userId) {
    return skillVerificationService.getProgressForUser(
        userId, SecurityUtils.getCurrentUserIdOrNull());
  }
}
