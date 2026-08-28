package com.socialapp.bookstore.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.socialapp.bookstore.service.MomoService;
import com.socialapp.moderation.service.BanDetailsService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * The security case for {@link DevPaymentController}, which is the endpoint's only guard: with no
 * {@code dev} profile active, the bean is never created and the route does not exist.
 *
 * <p>Deliberately a separate class from {@link DevPaymentControllerTest} rather than a nested one,
 * because {@code @ActiveProfiles} is inherited by {@code @Nested} classes by default — the two
 * assertions genuinely need two application contexts.
 *
 * <p>The request here carries a valid token on purpose. An anonymous one would be rejected by
 * Spring Security before routing and would answer 401 whether the controller existed or not, so it
 * would prove nothing. A fully authenticated caller getting 404 is the actual claim: a signed-in
 * user on a production deployment cannot mark their own purchase paid.
 */
@WebMvcTest(DevPaymentController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class DevPaymentControllerDisabledTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private MomoService momoService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private BanDetailsService banDetailsService;
  @MockBean private UserRepository userRepository;

  private static final String VALID_TOKEN = "a-valid-jwt-token";

  @BeforeEach
  void setUpDefaultUser() {
    UserEntity currentUser = new UserEntity();
    currentUser.setId(1);
    currentUser.setEmail("buyer@example.com");
    currentUser.setUsername("buyer");
    currentUser.setFullName("Buyer One");
    currentUser.setRole(UserRole.USER);
    currentUser.setEmailVerified(true);

    when(jwtProvider.isTokenValid(VALID_TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(VALID_TOKEN)).thenReturn(currentUser.getEmail());
    when(userRepository.findByEmailIgnoreCase(currentUser.getEmail()))
        .thenReturn(Optional.of(currentUser));
  }

  @Test
  @DisplayName("shouldReturn404_whenTheDevProfileIsNotActive")
  void shouldReturn404_whenTheDevProfileIsNotActive() throws Exception {
    // Given: no @ActiveProfiles, i.e. what a deployment looks like when SPRING_PROFILES_ACTIVE is
    // unset — the exact case that made @Profile("dev") the right gate instead of @Profile("!prod").

    // When
    MvcResult result =
        mockMvc
            .perform(
                post("/v1/api/payments/txn-abc/dev-settle")
                    .header("Authorization", "Bearer " + VALID_TOKEN))
            .andReturn();

    // Then: no handler is mapped to the path at all — which is the claim, and is asserted on the
    // resolved exception rather than on the status code on purpose. The status here is 500, not
    // 404, because GlobalExceptionHandler has no @ExceptionHandler for NoResourceFoundException
    // and its catch-all Exception mapping swallows it; that is a pre-existing defect affecting
    // EVERY unknown URL in the application, not something about this endpoint, and pinning 404
    // here would make this test fail the day it is fixed.
    assertThat(result.getResolvedException()).isInstanceOf(NoResourceFoundException.class);
    assertThat(result.getResponse().getStatus()).isNotEqualTo(200);
    verifyNoInteractions(momoService);
  }
}
