package com.socialapp.bookstore.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialapp.bookstore.service.MomoService;
import com.socialapp.common.exception.NotFoundException;
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
 * System/API integration tests for {@link DevPaymentController}, per ISTQB CTFL v4.0.1 Section
 * 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link MomoService} is mocked.
 *
 * <p><b>{@code @ActiveProfiles} is load-bearing here, not boilerplate.</b> The controller is
 * annotated {@code @Profile("dev")}, so without it there is no bean and every case below would 404
 * — which is exactly the behaviour {@link DevPaymentControllerDisabledTest} asserts, separately,
 * because it is the whole security argument for the endpoint.
 */
@WebMvcTest(DevPaymentController.class)
// "test" comes from build.gradle's `systemProperty 'spring.profiles.active', 'test'`, which
// supplies the test JWT secret and disables scheduling. @ActiveProfiles REPLACES that rather than
// adding to it, so naming "dev" alone drops the test profile and the context then fails to start
// on JwtProperties#secret. Both, in this order.
@ActiveProfiles({"test", "dev"})
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class DevPaymentControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private MomoService momoService;
  @MockBean private JwtProvider jwtProvider;

  @MockBean
  private BanDetailsService
      banDetailsService; // JwtAuthenticationFilter builds the banned-account 403 through it

  @MockBean private UserRepository userRepository;

  private static final String PAYMENTS_URL = "/v1/api/payments";
  private static final String VALID_TOKEN = "a-valid-jwt-token";

  private UserEntity currentUser;

  @BeforeEach
  void setUpDefaultUser() {
    currentUser = new UserEntity();
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

  private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + VALID_TOKEN);
  }

  // =====================================================================
  // POST /v1/api/payments/{transactionRef}/dev-settle
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/payments/{transactionRef}/dev-settle")
  class DevSettleTests {

    @Test
    @DisplayName("shouldReturn200AndPaidTrue_happyPath")
    void shouldReturn200AndPaidTrue_happyPath() throws Exception {
      // Given
      when(momoService.settleAsPaidForDevelopment(1, "txn-abc")).thenReturn(true);

      // When / Then: the same {transactionRef, paid} shape /sync answers with, so the client can
      // reuse its poll handler rather than learn a second format for a dev-only route.
      mockMvc
          .perform(authed(post(PAYMENTS_URL + "/txn-abc/dev-settle")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.transactionRef").value("txn-abc"))
          .andExpect(jsonPath("$.paid").value(true));

      verify(momoService).settleAsPaidForDevelopment(1, "txn-abc");
    }

    @Test
    @DisplayName("shouldReturn404_whenTheRefBelongsToSomebodyElse")
    void shouldReturn404_whenTheRefBelongsToSomebodyElse() throws Exception {
      // Given: the service 404s rather than 403s, so a stranger's ref is not even confirmed to
      // exist. This endpoint needs no agreement from MoMo, so an unguarded ref would be a free
      // copy of anyone's book.
      when(momoService.settleAsPaidForDevelopment(anyInt(), anyString()))
          .thenThrow(new NotFoundException("Purchase not found: txn-someone-else"));

      // When / Then
      mockMvc
          .perform(authed(post(PAYMENTS_URL + "/txn-someone-else/dev-settle")))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // Given: only the `dev` profile gates the bean's existence — it does NOT relax security, so
      // an anonymous caller reaching a dev machine still gets nothing.

      // When / Then
      mockMvc
          .perform(post(PAYMENTS_URL + "/txn-abc/dev-settle"))
          .andExpect(status().isUnauthorized());
      verifyNoInteractions(momoService);
    }

    @Test
    @DisplayName("shouldNotCollideWithTheMomoWebhookPath")
    void shouldNotCollideWithTheMomoWebhookPath() throws Exception {
      // Given: SecurityConfig permitAll()s the literal /v1/api/payments/momo/webhook, and this
      // controller's mapping is two segments deep as well. "momo" landing in {transactionRef}
      // must not inherit that exemption, or the one unauthenticated path in this module would
      // become a way to settle orders.

      // When / Then
      mockMvc.perform(post(PAYMENTS_URL + "/momo/dev-settle")).andExpect(status().isUnauthorized());
      verify(momoService, org.mockito.Mockito.never())
          .settleAsPaidForDevelopment(any(), anyString());
    }
  }
}
