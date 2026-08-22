package com.socialapp.security.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Guards the switch itself, not any one endpoint.
 *
 * <p>{@code @PreAuthorize} is silently inert without {@code @EnableMethodSecurity}: nothing fails at
 * startup, the annotation still parses, and the endpoint simply stops being guarded. That is how
 * five admin-only roadmap and skill-verification endpoints ended up open to every signed-in user.
 *
 * <p>The controller tests cannot catch a regression on their own, because {@code SecurityConfig}
 * now also carries duplicate path rules for those endpoints — the 403s would keep passing while
 * method security was off, and any {@code @PreAuthorize} added elsewhere in the codebase would be
 * unguarded again with no test to notice. This is the assertion that fails in that case.
 */
class MethodSecurityEnabledTest {

  @Test
  @DisplayName("shouldEnableMethodSecurity_soThatEveryPreAuthorizeInTheCodebaseIsEnforced")
  void shouldEnableMethodSecurity() {
    EnableMethodSecurity annotation =
        AnnotatedElementUtils.findMergedAnnotation(
            SecurityConfig.class, EnableMethodSecurity.class);

    assertThat(annotation)
        .as(
            "SecurityConfig must be annotated @EnableMethodSecurity — without it every "
                + "@PreAuthorize in the application is ignored and the annotated endpoints are "
                + "open to any authenticated caller")
        .isNotNull();

    assertThat(annotation.prePostEnabled())
        .as("@PreAuthorize/@PostAuthorize support must stay on")
        .isTrue();
  }
}
