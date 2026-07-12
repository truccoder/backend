package com.socialapp.bookstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.socialapp.bookstore.config.MomoProperties;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.entity.BookPurchaseEntity;
import com.socialapp.bookstore.entity.enums.PaymentStatus;
import com.socialapp.bookstore.repository.BookPurchaseRepository;
import com.socialapp.common.exception.PaymentException;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.security.repository.UserRepository;

import reactor.core.publisher.Mono;

/**
 * Stage 5 (Experience-based Testing) — Error Guessing technique per ISTQB CTFL v4.0.1 Section
 * 4.5.3, applied to {@link MomoService} — the MoMo payment gateway integration.
 *
 * <p>Before this fix, {@code postForMap} had no exception handling at all around its {@link
 * WebClient} call: a MoMo timeout/network error/5xx would propagate as a raw third-party
 * exception straight out of {@code createPayment}/{@code syncPaymentStatus}. {@code
 * handleWebhook} could also crash with a raw {@link NumberFormatException} if MoMo's IPN payload
 * had a missing or non-numeric {@code resultCode} — unlike a signature mismatch, which was
 * already handled gracefully (logged and ignored). Both gaps are fixed and locked in here; the
 * {@code hmacSHA256} failure mode (empty/misconfigured secret key) is covered in {@code
 * MomoServiceTest#shouldThrowPaymentException_whenHmacGenerationFails}.
 *
 * <p>{@link WebClient} is mocked — no real HTTP call to MoMo is ever made.
 */
@ExtendWith(MockitoExtension.class)
class MomoServiceErrorGuessingTest {

  private static final Integer BOOK_ID = 50;
  private static final Integer AUTHOR_ID = 1;
  private static final Integer BUYER_ID = 2;

  @Mock private BookPurchaseRepository purchaseRepository;
  @Mock private BookService bookService;
  @Mock private WebClient momoWebClient;
  @Mock private UserRepository userRepository;
  @Mock private NotificationService notificationService;

  private MomoProperties momoProperties;
  private MomoService momoService;

  @BeforeEach
  void setUp() {
    momoProperties = new MomoProperties();
    momoProperties.setRedirectUrl("http://localhost:3000/payment/success");
    momoProperties.setIpnUrl("http://localhost:8080/webhook");
    momoService =
        new MomoService(
            momoProperties,
            purchaseRepository,
            bookService,
            momoWebClient,
            userRepository,
            notificationService);
  }

  private static BookEntity paidBook() {
    return BookEntity.builder()
        .id(BOOK_ID)
        .authorId(AUTHOR_ID)
        .price(1000L)
        .title("Book")
        .isFree(false)
        .build();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void stubMomoFailure(Mono errorMono) {
    WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
    WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
    WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

    when(momoWebClient.post()).thenReturn(uriSpec);
    when(uriSpec.uri(anyString())).thenReturn(bodySpec);
    when(bodySpec.bodyValue(any())).thenReturn(headersSpec);
    when(headersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.bodyToMono(Map.class)).thenReturn(errorMono);
  }

  // =====================================================================
  // MoMo gateway outages during createPayment
  // =====================================================================

  @Nested
  @DisplayName("createPayment: MoMo gateway outages")
  class CreatePaymentOutageTests {

    @Test
    @DisplayName("shouldThrowPaymentException_whenMomoConnectionTimesOut")
    void shouldThrowPaymentException_whenMomoConnectionTimesOut() {
      // Given — MoMo accepted the connection but never responded in time
      when(bookService.findBookOrThrow(BOOK_ID)).thenReturn(paidBook());
      when(purchaseRepository.findByBookIdAndBuyerId(BOOK_ID, BUYER_ID))
          .thenReturn(Optional.empty());
      stubMomoFailure(Mono.error(new SocketTimeoutException("Read timed out")));

      // When / Then — must surface as a typed PaymentException, not a raw third-party exception.
      // Reactor's Mono.error(...).block() wraps checked exceptions in its own
      // reactor.core.Exceptions.ReactiveException, so the SocketTimeoutException itself is the
      // root cause, one level deeper than PaymentException's direct cause.
      assertThatThrownBy(() -> momoService.createPayment(BUYER_ID, BOOK_ID))
          .isInstanceOf(PaymentException.class)
          .hasMessageContaining("Failed to reach MoMo")
          .rootCause()
          .isInstanceOf(SocketTimeoutException.class);
    }

    @Test
    @DisplayName("shouldThrowPaymentException_whenMomoConnectionIsRefused")
    void shouldThrowPaymentException_whenMomoConnectionIsRefused() {
      // Given — MoMo's gateway host is unreachable
      when(bookService.findBookOrThrow(BOOK_ID)).thenReturn(paidBook());
      when(purchaseRepository.findByBookIdAndBuyerId(BOOK_ID, BUYER_ID))
          .thenReturn(Optional.empty());
      stubMomoFailure(Mono.error(new ConnectException("Connection refused")));

      // When / Then
      assertThatThrownBy(() -> momoService.createPayment(BUYER_ID, BOOK_ID))
          .isInstanceOf(PaymentException.class)
          .rootCause()
          .isInstanceOf(ConnectException.class);
    }

    @Test
    @DisplayName("shouldThrowPaymentException_whenMomoReturns500")
    void shouldThrowPaymentException_whenMomoReturns500() {
      // Given — MoMo responded, but with a genuine server-side failure
      when(bookService.findBookOrThrow(BOOK_ID)).thenReturn(paidBook());
      when(purchaseRepository.findByBookIdAndBuyerId(BOOK_ID, BUYER_ID))
          .thenReturn(Optional.empty());
      stubMomoFailure(
          Mono.error(
              WebClientResponseException.create(
                  500, "Internal Server Error", null, new byte[0], null)));

      // When / Then
      assertThatThrownBy(() -> momoService.createPayment(BUYER_ID, BOOK_ID))
          .isInstanceOf(PaymentException.class)
          .hasCauseInstanceOf(WebClientResponseException.class);
    }

    @Test
    @DisplayName("shouldPreserveOriginalCauseChain_whenMomoClientFails")
    void shouldPreserveOriginalCauseChain_whenMomoClientFails() {
      // Given — diagnosability matters: the original low-level exception must not be swallowed
      when(bookService.findBookOrThrow(BOOK_ID)).thenReturn(paidBook());
      when(purchaseRepository.findByBookIdAndBuyerId(BOOK_ID, BUYER_ID))
          .thenReturn(Optional.empty());
      SocketTimeoutException original = new SocketTimeoutException("Read timed out");
      stubMomoFailure(Mono.error(original));

      // When / Then
      assertThatThrownBy(() -> momoService.createPayment(BUYER_ID, BOOK_ID))
          .isInstanceOf(PaymentException.class)
          .rootCause()
          .isSameAs(original);
    }
  }

  // =====================================================================
  // MoMo gateway outages during syncPaymentStatus
  // =====================================================================

  @Nested
  @DisplayName("syncPaymentStatus: MoMo gateway outages")
  class SyncPaymentStatusOutageTests {

    @Test
    @DisplayName("shouldThrowPaymentException_whenMomoQueryTimesOut")
    void shouldThrowPaymentException_whenMomoQueryTimesOut() {
      // Given
      BookPurchaseEntity purchase =
          BookPurchaseEntity.builder()
              .id(9)
              .bookId(BOOK_ID)
              .buyerId(BUYER_ID)
              .paymentStatus(PaymentStatus.PENDING)
              .transactionRef("REF1")
              .updatedAt(OffsetDateTime.now())
              .build();
      when(purchaseRepository.findByTransactionRef("REF1")).thenReturn(Optional.of(purchase));
      stubMomoFailure(Mono.error(new SocketTimeoutException("Read timed out")));

      // When / Then
      assertThatThrownBy(() -> momoService.syncPaymentStatus("REF1"))
          .isInstanceOf(PaymentException.class)
          .hasMessageContaining("Failed to reach MoMo")
          .rootCause()
          .isInstanceOf(SocketTimeoutException.class);
    }
  }

  // =====================================================================
  // handleWebhook: malformed IPN payload from MoMo
  // =====================================================================

  @Nested
  @DisplayName("handleWebhook: malformed IPN payload")
  class MalformedWebhookTests {

    private Map<String, Object> signedPayload(Object resultCode) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("orderId", "REF1");
      if (resultCode != null) {
        payload.put("resultCode", resultCode);
      }
      payload.put("signature", computeIpnSignature(payload));
      return payload;
    }

    /** Replicates {@code MomoService#buildIpnSignature} so the signature check passes. */
    private String computeIpnSignature(Map<String, Object> payload) {
      String rawSignature =
          "accessKey="
              + momoProperties.getAccessKey()
              + "&amount="
              + stringify(payload.get("amount"))
              + "&extraData="
              + stringify(payload.get("extraData"))
              + "&message="
              + stringify(payload.get("message"))
              + "&orderId="
              + stringify(payload.get("orderId"))
              + "&orderInfo="
              + stringify(payload.get("orderInfo"))
              + "&orderType="
              + stringify(payload.get("orderType"))
              + "&partnerCode="
              + stringify(payload.get("partnerCode"))
              + "&payType="
              + stringify(payload.get("payType"))
              + "&requestId="
              + stringify(payload.get("requestId"))
              + "&responseTime="
              + stringify(payload.get("responseTime"))
              + "&resultCode="
              + stringify(payload.get("resultCode"))
              + "&transId="
              + stringify(payload.get("transId"));
      return hmacSHA256(momoProperties.getSecretKey(), rawSignature);
    }

    private String stringify(Object value) {
      return value == null ? "" : String.valueOf(value);
    }

    private String hmacSHA256(String key, String data) {
      try {
        javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
        hmac.init(
            new javax.crypto.spec.SecretKeySpec(
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = hmac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
          sb.append(String.format("%02x", b));
        }
        return sb.toString();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Test
    @DisplayName("shouldReturnFalse_notThrowNumberFormatException_whenResultCodeIsMissing")
    void shouldReturnFalse_notThrowNumberFormatException_whenResultCodeIsMissing() {
      // Given — MoMo's IPN payload omits resultCode entirely (a genuinely malformed callback)
      Map<String, Object> payload = signedPayload(null);

      // When / Then — must never crash the webhook endpoint with a raw NumberFormatException
      boolean result = momoService.handleWebhook(payload);
      assertThat(result).isFalse();
      verify(purchaseRepository, never()).findByTransactionRef(any());
    }

    @Test
    @DisplayName("shouldReturnFalse_notThrowNumberFormatException_whenResultCodeIsNonNumericText")
    void shouldReturnFalse_notThrowNumberFormatException_whenResultCodeIsNonNumericText() {
      // Given — resultCode present but not a valid integer
      Map<String, Object> payload = signedPayload("not-a-number");

      // When / Then
      boolean result = momoService.handleWebhook(payload);
      assertThat(result).isFalse();
      verify(purchaseRepository, never()).findByTransactionRef(any());
    }
  }
}
