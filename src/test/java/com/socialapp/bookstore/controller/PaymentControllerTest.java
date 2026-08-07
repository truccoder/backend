package com.socialapp.bookstore.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialapp.bookstore.dto.PaymentResponseDto;
import com.socialapp.bookstore.service.MomoService;
import com.socialapp.common.exception.ValidationException;
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
 * System/API integration tests for {@link PaymentController}, per ISTQB CTFL v4.0.1 Section
 * 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link MomoService} is mocked.
 *
 * <p><b>Mixed security posture on one controller</b> — unlike every other controller tested so
 * far, this one is <i>not</i> uniformly authenticated: {@link SecurityConfig} carves out {@code
 * /v1/api/payments/momo/webhook} as {@code permitAll()} (MoMo's payment gateway calls this
 * server-to-server with no user session), while {@code createPayment} and {@code
 * syncPaymentStatus} still require a real bearer token. {@link WebhookTests} proves the webhook
 * is reachable with no {@code Authorization} header, exactly as {@code AuthController}'s endpoints
 * were, while the other two nested classes use the same real-{@link JwtAuthenticationFilter}
 * simulation as the rest of this suite.
 */
@WebMvcTest(PaymentController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class PaymentControllerTest {

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
  // POST /v1/api/payments/books/{bookId}
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/payments/books/{bookId}")
  class CreatePaymentTests {

    @Test
    @DisplayName("shouldReturn200AndPaymentDetails_happyPath")
    void shouldReturn200AndPaymentDetails_happyPath() throws Exception {
      // Given
      when(momoService.createPayment(currentUser.getId(), 1))
          .thenReturn(
              PaymentResponseDto.builder()
                  .paymentUrl("https://test-payment.momo.vn/pay/abc")
                  .transactionRef("txn-abc")
                  .build());

      // When / Then
      mockMvc
          .perform(authed(post(PAYMENTS_URL + "/books/1")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.transactionRef").value("txn-abc"))
          .andExpect(jsonPath("$.paymentUrl").value("https://test-payment.momo.vn/pay/abc"));
    }

    @Test
    @DisplayName("shouldReturn400_whenBookIsFree")
    void shouldReturn400_whenBookIsFree() throws Exception {
      // Given
      when(momoService.createPayment(anyInt(), anyInt()))
          .thenThrow(new ValidationException("This book is free, no payment required"));

      // When / Then
      mockMvc
          .perform(authed(post(PAYMENTS_URL + "/books/1")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("This book is free, no payment required"));
    }

    @Test
    @DisplayName("shouldReturn400_whenBookAlreadyPurchased")
    void shouldReturn400_whenBookAlreadyPurchased() throws Exception {
      // Given
      when(momoService.createPayment(anyInt(), anyInt()))
          .thenThrow(new ValidationException("You have already purchased this book"));

      // When / Then
      mockMvc.perform(authed(post(PAYMENTS_URL + "/books/1"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(post(PAYMENTS_URL + "/books/1")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // POST /v1/api/payments/momo/webhook (permitAll)
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/payments/momo/webhook")
  class WebhookTests {

    @Test
    @DisplayName("shouldReturn200_whenCalledWithNoAuthorizationHeader_permitAll")
    void shouldReturn200_whenCalledWithNoAuthorizationHeader_permitAll() throws Exception {
      // Given — MoMo calls this server-to-server; SecurityConfig explicitly permitAll()s it
      String payload =
          """
          { "transId": 123456, "resultCode": 0, "orderId": "txn-abc" }
          """;

      // When / Then
      mockMvc
          .perform(
              post(PAYMENTS_URL + "/momo/webhook")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(payload))
          .andExpect(status().isOk());

      verify(momoService).handleWebhook(any());
    }
  }

  // =====================================================================
  // POST /v1/api/payments/{transactionRef}/sync
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/payments/{transactionRef}/sync")
  class SyncPaymentStatusTests {

    @Test
    @DisplayName("shouldReturn200AndPaidTrue_whenPaymentIsConfirmed_happyPath")
    void shouldReturn200AndPaidTrue_whenPaymentIsConfirmed_happyPath() throws Exception {
      // Given
      when(momoService.syncPaymentStatus("txn-abc")).thenReturn(true);

      // When / Then
      mockMvc
          .perform(authed(post(PAYMENTS_URL + "/txn-abc/sync")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.transactionRef").value("txn-abc"))
          .andExpect(jsonPath("$.paid").value(true));
    }

    @Test
    @DisplayName("shouldReturn200AndPaidFalse_whenPaymentIsNotYetConfirmed")
    void shouldReturn200AndPaidFalse_whenPaymentIsNotYetConfirmed() throws Exception {
      // Given
      when(momoService.syncPaymentStatus("txn-pending")).thenReturn(false);

      // When / Then
      mockMvc
          .perform(authed(post(PAYMENTS_URL + "/txn-pending/sync")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.paid").value(false));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(post(PAYMENTS_URL + "/txn-abc/sync")).andExpect(status().isUnauthorized());
    }
  }
}
