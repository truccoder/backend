package com.socialapp.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.security.dto.RegisterRequestDto;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import jakarta.persistence.EntityManager;

/**
 * Regression guard for email/password registration against a real Postgres (Testcontainers, see
 * {@link AbstractIntegrationTest}) rather than the Mockito doubles of {@code AuthServiceTest}.
 *
 * <p>The bug these tests exist for could not be seen through a mocked repository at all. {@code
 * AuthService.register()} used to persist the user and only then decide the handle. Hibernate
 * captures a row's column values at {@code persist()}, not at flush, so the handle assigned after
 * {@code save()} never reached the INSERT — it reached the database only as a follow-up UPDATE,
 * long after {@code V47}'s NOT NULL constraint had rejected the INSERT that carried {@code
 * username} NULL. A mocked repository neither flushes nor constrains anything, so the unit tests
 * stayed green while every real signup returned 409 "This action conflicts with existing data".
 * Only a persistence context and a real schema can catch that, which is what these tests bring —
 * each one calls {@code userRepository.flush()} so the INSERT is actually sent to Postgres inside
 * the test rather than at a commit the rollback would skip.
 *
 * <p>{@code @Transactional} so each test rolls back its own writes — the containers in {@link
 * AbstractIntegrationTest} are started once and reused for the whole JVM run.
 */
@Transactional
class AuthServiceRegistrationIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "password123";

  @Autowired private AuthService authService;
  @Autowired private UserRepository userRepository;
  @Autowired private EntityManager entityManager;

  @Test
  @DisplayName(
      "should persist a signup that sends no handle, deriving one from the full name instead of"
          + " hitting the username NOT NULL constraint")
  void shouldPersistRegistration_whenRequestOmitsUsername() {
    // Given: the three-field body the frontend's signup form actually sends — no username
    String email = uniqueEmail();
    RegisterRequestDto request = new RegisterRequestDto(email, PASSWORD, "Trần Phú Thịnh", null);

    // When
    authService.register(request, null);
    userRepository.flush();

    // Then: the row reached Postgres, with the handle V47 requires every user to have
    UserEntity saved = findFresh(email);
    assertThat(saved.getUsername()).isEqualTo("tran-phu-thinh");
    assertThat(saved.getFullName()).isEqualTo("Trần Phú Thịnh");
    assertThat(saved.isEmailVerified()).isFalse();
  }

  @Test
  @DisplayName("should suffix the user id when the derived handle is already taken")
  void shouldSuffixDerivedHandle_whenItCollidesWithAnExistingUser() {
    // Given: someone already holds the handle this name derives to
    authService.register(
        new RegisterRequestDto(uniqueEmail(), PASSWORD, "Trần Phú Thịnh", null), null);
    userRepository.flush();

    // When: a namesake registers
    String email = uniqueEmail();
    authService.register(new RegisterRequestDto(email, PASSWORD, "Tran Phu Thinh", null), null);
    userRepository.flush();

    // Then: the second one is disambiguated rather than rejected — they did not choose this
    // handle and can do nothing about the clash
    UserEntity saved = findFresh(email);
    assertThat(saved.getUsername()).isEqualTo("tran-phu-thinh-" + saved.getId());
  }

  @Test
  @DisplayName("should fall back to the id when the full name yields no usable handle")
  void shouldFallBackToId_whenFullNameSlugsToNothing() {
    // Given: a name made of punctuation only, which slugs to nothing
    String email = uniqueEmail();

    // When
    authService.register(new RegisterRequestDto(email, PASSWORD, "!!!", null), null);
    userRepository.flush();

    // Then: NOT NULL is still satisfied, by the one value guaranteed to be unique
    UserEntity saved = findFresh(email);
    assertThat(saved.getUsername()).isEqualTo("user-" + saved.getId());
  }

  @Test
  @DisplayName("should keep the handle the caller chose, and store it")
  void shouldPersistChosenHandle_whenRequestSendsOne() {
    // Given: a client that has added the optional username field
    String email = uniqueEmail();
    String chosen = "ada-" + System.nanoTime();

    // When
    authService.register(new RegisterRequestDto(email, PASSWORD, "Ada Lovelace", chosen), null);
    userRepository.flush();

    // Then
    assertThat(findFresh(email).getUsername()).isEqualTo(chosen);
  }

  @Test
  @DisplayName(
      "should reject a chosen handle that is taken with a 400-style ValidationException, not a"
          + " constraint violation from the database")
  void shouldRejectTakenHandle_beforeItReachesTheUniqueIndex() {
    // Given: a handle someone already holds
    String chosen = "ada-" + System.nanoTime();
    authService.register(
        new RegisterRequestDto(uniqueEmail(), PASSWORD, "Ada Lovelace", chosen), null);
    userRepository.flush();

    // When / Then: the app-level check answers, so the caller is told what is wrong instead of
    // getting the generic conflict the uq_users_username_lower index would have produced
    RegisterRequestDto duplicate =
        new RegisterRequestDto(uniqueEmail(), PASSWORD, "Ada Byron", chosen);
    assertThatThrownBy(() -> authService.register(duplicate, null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Username already taken");
  }

  @Test
  @DisplayName("should register a two-letter name by padding the handle to the legal minimum")
  void shouldPadShortHandle_whenFullNameSlugsBelowThreeCharacters() {
    // Given: a real, if short, name — refusing to register it would be worse than padding it
    String email = uniqueEmail();

    // When
    assertThatCode(
            () -> authService.register(new RegisterRequestDto(email, PASSWORD, "Vũ", null), null))
        .doesNotThrowAnyException();
    userRepository.flush();

    // Then
    UserEntity saved = findFresh(email);
    assertThat(saved.getUsername()).isEqualTo("vu-" + saved.getId());
  }

  /** Reads the row back through a fresh query, so the assertions see what Postgres holds. */
  private UserEntity findFresh(String email) {
    entityManager.clear();
    return userRepository.findByEmailIgnoreCase(email).orElseThrow();
  }

  private String uniqueEmail() {
    return "signup-" + System.nanoTime() + "@fpt.edu.vn";
  }
}
