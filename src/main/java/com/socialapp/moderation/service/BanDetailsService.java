package com.socialapp.moderation.service;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.moderation.dto.BanDetailsDto;
import com.socialapp.moderation.entity.UserViolationEntity;
import com.socialapp.moderation.repository.UserViolationRepository;

import lombok.RequiredArgsConstructor;

/**
 * Answers "why is this account locked, and until when" for the places that have to tell the person
 * themselves: the 403 from {@code JwtAuthenticationFilter} and the 403 from a login attempt.
 *
 * <p>One query, and only on a request that is already being refused — a banned user gets this
 * lookup instead of a page, not in addition to one.
 */
@Service
@RequiredArgsConstructor
public class BanDetailsService {

  private final UserViolationRepository userViolationRepository;

  /**
   * The ban, described.
   *
   * <p>The <b>most recent</b> violation, not the one that happened to cross the threshold: a ban
   * follows the last straw, and that is the one the user just did and will recognise. If there is
   * no violation on record — a ban applied directly in the database, or one that predates the
   * violation log — the type and reason come back null rather than guessed, and the client is left
   * with the date, which is still more than the prose sentence gave it.
   */
  @Transactional(readOnly = true)
  public BanDetailsDto describe(Integer userId, OffsetDateTime bannedUntil) {
    List<UserViolationEntity> violations =
        userViolationRepository.findByUserIdOrderByCreatedAtDesc(userId);

    UserViolationEntity latest = violations.isEmpty() ? null : violations.get(0);

    return BanDetailsDto.builder()
        .bannedUntil(bannedUntil)
        .violationType(latest == null ? null : latest.getViolationType())
        .reason(latest == null ? null : latest.getDescription())
        .build();
  }
}
