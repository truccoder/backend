package com.socialapp.bookstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import com.socialapp.bookstore.config.MomoProperties;
import com.socialapp.bookstore.dto.PaymentResponseDto;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.entity.BookPurchaseEntity;
import com.socialapp.bookstore.entity.enums.PaymentStatus;
import com.socialapp.bookstore.repository.BookPurchaseRepository;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import reactor.core.publisher.Mono;

/**
 * Component (unit) tests for {@link MomoService}, per ISTQB CTFL v4.0.1 (Section 2.2.1 component
 * testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3 BDD
 * Given/When/Then) — see {@code PostServiceTest} for the full rationale.
 *
 * <p>{@link WebClient}'s fluent chain ({@code post().uri().bodyValue().retrieve().bodyToMono()})
 * is mocked stage-by-stage via {@link #stubMomoResponse(Mono)} so no real HTTP call to MoMo is
 * ever made. {@link MomoProperties} is a plain settings holder, not a collaborator with behavior,
 * so a real instance is used instead of a mock.
 */
@ExtendWith(MockitoExtension.class)
class MomoServiceTest {

  private static final Integer BOOK_ID = 50;
  private static final Integer AUTHOR_ID = 1;
  private static final Integer BUYER_ID = 2;

  @Mock private BookPurchaseRepository purchaseRepository;
  @Mock private BookService bookService;
  @Mock private WebClient momoWebClient;
  @Mock private UserRepository userRepository;
  @Mock private NotificationService notificationService;

  @Captor private ArgumentCaptor<BookPurchaseEntity> purchaseCaptor;
  @Captor private ArgumentCaptor<SendNotificationRequest> notificationCaptor;
  @Captor private ArgumentCaptor<Map> requestBodyCaptor;

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

  // ---------------------------------------------------------------------
  // Test data builders
  // ---------------------------------------------------------------------

  private static BookEntity paidBook(Integer id, Integer authorId, long price, String title) {
    return BookEntity.builder()
        .id(id)
        .authorId(authorId)
        .price(price)
        .title(title)
        .isFree(false)
        .build();
  }

  private static BookPurchaseEntity existingPurchase(
      PaymentStatus status, String transactionRef, OffsetDateTime updatedAt) {
    return BookPurchaseEntity.builder()
        .id(9)
        .bookId(BOOK_ID)
        .buyerId(BUYER_ID)
        .paymentStatus(status)
        .transactionRef(transactionRef)
        .updatedAt(updatedAt)
        .build();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void stubMomoResponse(Mono responseMono) {
    WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
    WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
    WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

    when(momoWebClient.post()).thenReturn(uriSpec);
    when(uriSpec.uri(anyString())).thenReturn(bodySpec);
    when(bodySpec.bodyValue(requestBodyCaptor.capture())).thenReturn(headersSpec);
    when(headersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.bodyToMono(Map.class)).thenReturn(responseMono);
  }

  private static Map<String, Object> successResponse() {
    Map<String, Object> response = new HashMap<>();
    response.put("resultCode", 0);
    response.put("payUrl", "https://test-payment.momo.vn/pay/abc");
    response.put("qrCodeUrl", "https://test-payment.momo.vn/qr/abc");
    response.put("transId", "9876543210");
    return response;
  }

  /** Replicates {@code MomoService#buildIpnSignature} so tests can craft a validly-signed IPN payload. */
  private Map<String, Object> validIpnPayload(
      String orderId, int resultCode, String transId, String payType) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("orderId", orderId);
    payload.put("resultCode", resultCode);
    payload.put("transId", transId);
    if (payType != null) {
      payload.put("payType", payType);
    }
    payload.put("signature", computeIpnSignature(payload));
    return payload;
  }

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

  private static String stringify(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String hmacSHA256(String key, String data) {
    try {
      Mac hmac = Mac.getInstance("HmacSHA256");
      hmac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] hash = hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // =====================================================================
  // createPayment
  // =====================================================================

  @Nested
  @DisplayName("createPayment")
  class CreatePaymentTests {

    @Test
    @DisplayName("should reject a free book")
    void shouldRejectFreeBook() {
      // Given
      BookEntity freeBook =
          BookEntity.builder().id(BOOK_ID).authorId(AUTHOR_ID).isFree(true).build();
      when(bookService.findBookOrThrow(BOOK_ID)).thenReturn(freeBook);

      // When / Then
      assertThatThrownBy(() -> momoService.createPayment(BUYER_ID, BOOK_ID))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("free, no payment required");
    }

    @Test
    @DisplayName("should reject the author purchasing their own book")
    void shouldRejectPurchasingOwnBook() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, "Book"));

      // When / Then
      assertThatThrownBy(() -> momoService.createPayment(AUTHOR_ID, BOOK_ID))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Cannot purchase your own book");
    }

    @Test
    @DisplayName("should reject when the book was already purchased")
    void shouldRejectWhenAlreadyPurchased() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, "Book"));
      when(purchaseRepository.findByBookIdAndBuyerId(BOOK_ID, BUYER_ID))
          .thenReturn(
              Optional.of(existingPurchase(PaymentStatus.COMPLETED, "REF1", OffsetDateTime.now())));

      // When / Then
      assertThatThrownBy(() -> momoService.createPayment(BUYER_ID, BOOK_ID))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("already purchased");
    }

    @Test
    @DisplayName("should reject when a still-fresh pending payment is in progress")
    void shouldRejectWhenPendingPaymentStillFresh() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, "Book"));
      when(purchaseRepository.findByBookIdAndBuyerId(BOOK_ID, BUYER_ID))
          .thenReturn(
              Optional.of(
                  existingPurchase(
                      PaymentStatus.PENDING, "REF1", OffsetDateTime.now().minusMinutes(5))));

      // When / Then
      assertThatThrownBy(() -> momoService.createPayment(BUYER_ID, BOOK_ID))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("already in progress");
    }

    @Test
    @DisplayName("should allow a new attempt when the existing pending payment is stale")
    void shouldAllowNewAttempt_whenExistingPendingPaymentIsStale() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, "Book"));
      BookPurchaseEntity stale =
          existingPurchase(PaymentStatus.PENDING, "OLD-REF", OffsetDateTime.now().minusMinutes(30));
      when(purchaseRepository.findByBookIdAndBuyerId(BOOK_ID, BUYER_ID))
          .thenReturn(Optional.of(stale));
      stubMomoResponse(Mono.just(successResponse()));

      // When
      PaymentResponseDto dto = momoService.createPayment(BUYER_ID, BOOK_ID);

      // Then
      assertThat(dto.getPaymentUrl()).isEqualTo("https://test-payment.momo.vn/pay/abc");
      verify(purchaseRepository).save(purchaseCaptor.capture());
      assertThat(purchaseCaptor.getValue()).isSameAs(stale);
      assertThat(stale.getTransactionRef()).isNotEqualTo("OLD-REF");
      assertThat(stale.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName(
        "should allow a new attempt when the existing pending payment has no transaction ref")
    void shouldAllowNewAttempt_whenExistingPurchaseHasNoTransactionRef() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, "Book"));
      BookPurchaseEntity noRef =
          existingPurchase(PaymentStatus.PENDING, null, OffsetDateTime.now());
      when(purchaseRepository.findByBookIdAndBuyerId(BOOK_ID, BUYER_ID))
          .thenReturn(Optional.of(noRef));
      stubMomoResponse(Mono.just(successResponse()));

      // When / Then
      assertThat(momoService.createPayment(BUYER_ID, BOOK_ID)).isNotNull();
    }

    @Test
    @DisplayName(
        "should allow a new attempt when the existing pending payment has no updatedAt yet")
    void shouldAllowNewAttempt_whenExistingPurchaseHasNoUpdatedAt() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, "Book"));
      BookPurchaseEntity noUpdatedAt = existingPurchase(PaymentStatus.PENDING, "REF1", null);
      when(purchaseRepository.findByBookIdAndBuyerId(BOOK_ID, BUYER_ID))
          .thenReturn(Optional.of(noUpdatedAt));
      stubMomoResponse(Mono.just(successResponse()));

      // When / Then
      assertThat(momoService.createPayment(BUYER_ID, BOOK_ID)).isNotNull();
    }

    @Test
    @DisplayName("should allow a new attempt when the existing purchase is not pending")
    void shouldAllowNewAttempt_whenExistingPurchaseStatusIsNotPending() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, "Book"));
      BookPurchaseEntity failed =
          existingPurchase(PaymentStatus.FAILED, "REF1", OffsetDateTime.now());
      when(purchaseRepository.findByBookIdAndBuyerId(BOOK_ID, BUYER_ID))
          .thenReturn(Optional.of(failed));
      stubMomoResponse(Mono.just(successResponse()));

      // When / Then
      assertThat(momoService.createPayment(BUYER_ID, BOOK_ID)).isNotNull();
    }

    @Test
    @DisplayName("should create a brand new purchase row when none exists yet")
    void shouldCreateNewPurchase_whenNoneExists() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 5000L, "Book"));
      when(purchaseRepository.findByBookIdAndBuyerId(BOOK_ID, BUYER_ID))
          .thenReturn(Optional.empty());
      stubMomoResponse(Mono.just(successResponse()));

      // When
      PaymentResponseDto dto = momoService.createPayment(BUYER_ID, BOOK_ID);

      // Then
      assertThat(dto.getQrCode()).isEqualTo("https://test-payment.momo.vn/qr/abc");
      verify(purchaseRepository).save(purchaseCaptor.capture());
      BookPurchaseEntity saved = purchaseCaptor.getValue();
      assertThat(saved.getBookId()).isEqualTo(BOOK_ID);
      assertThat(saved.getBuyerId()).isEqualTo(BUYER_ID);
      assertThat(saved.getAmount()).isEqualTo(5000L);
      assertThat(saved.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
      assertThat(saved.getGatewayTransactionNo()).isNull();
      assertThat(saved.getPaidAt()).isNull();
    }

    @Test
    @DisplayName("should reject when MoMo rejects the payment link creation")
    void shouldThrowValidationException_whenMomoRejectsLinkCreation() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, "Book"));
      when(purchaseRepository.findByBookIdAndBuyerId(BOOK_ID, BUYER_ID))
          .thenReturn(Optional.empty());
      Map<String, Object> rejected = new HashMap<>();
      rejected.put("resultCode", 99);
      stubMomoResponse(Mono.just(rejected));

      // When / Then
      assertThatThrownBy(() -> momoService.createPayment(BUYER_ID, BOOK_ID))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Failed to create MoMo payment link");
    }

    @Test
    @DisplayName("should reject when MoMo returns no response body")
    void shouldThrowValidationException_whenMomoReturnsNoResponse() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, "Book"));
      when(purchaseRepository.findByBookIdAndBuyerId(BOOK_ID, BUYER_ID))
          .thenReturn(Optional.empty());
      stubMomoResponse(Mono.empty());

      // When / Then
      assertThatThrownBy(() -> momoService.createPayment(BUYER_ID, BOOK_ID))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("MoMo returned no response");
    }

    @Test
    @DisplayName("should accept a String-typed resultCode from MoMo's response")
    void shouldHandleStringResultCode_fromMomoResponse() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, "Book"));
      when(purchaseRepository.findByBookIdAndBuyerId(BOOK_ID, BUYER_ID))
          .thenReturn(Optional.empty());
      Map<String, Object> response = new HashMap<>();
      response.put("resultCode", "0");
      response.put("payUrl", "https://test-payment.momo.vn/pay/xyz");
      stubMomoResponse(Mono.just(response));

      // When / Then
      assertThat(momoService.createPayment(BUYER_ID, BOOK_ID).getPaymentUrl())
          .isEqualTo("https://test-payment.momo.vn/pay/xyz");
    }

    @Test
    @DisplayName("should truncate a long book title in the MoMo order info")
    void shouldTruncateLongBookTitleInOrderInfo() {
      // Given
      String longTitle = "A".repeat(150);
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, longTitle));
      when(purchaseRepository.findByBookIdAndBuyerId(BOOK_ID, BUYER_ID))
          .thenReturn(Optional.empty());
      stubMomoResponse(Mono.just(successResponse()));

      // When
      momoService.createPayment(BUYER_ID, BOOK_ID);

      // Then
      String orderInfo = (String) requestBodyCaptor.getValue().get("orderInfo");
      assertThat(orderInfo).hasSize(100);
    }

    @Test
    @DisplayName("should not truncate a short book title in the MoMo order info")
    void shouldNotTruncateShortBookTitleInOrderInfo() {
      // Given
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, "Short Title"));
      when(purchaseRepository.findByBookIdAndBuyerId(BOOK_ID, BUYER_ID))
          .thenReturn(Optional.empty());
      stubMomoResponse(Mono.just(successResponse()));

      // When
      momoService.createPayment(BUYER_ID, BOOK_ID);

      // Then
      String orderInfo = (String) requestBodyCaptor.getValue().get("orderInfo");
      assertThat(orderInfo).isEqualTo("Mua sach: Short Title");
    }

    @Test
    @DisplayName("should wrap HMAC generation failure as a RuntimeException")
    void shouldThrowRuntimeException_whenHmacGenerationFails() {
      // Given
      momoProperties.setSecretKey(null);
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, "Book"));
      when(purchaseRepository.findByBookIdAndBuyerId(BOOK_ID, BUYER_ID))
          .thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> momoService.createPayment(BUYER_ID, BOOK_ID))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Failed to generate HMAC");
    }
  }

  // =====================================================================
  // handleWebhook
  // =====================================================================

  @Nested
  @DisplayName("handleWebhook")
  class HandleWebhookTests {

    @Test
    @DisplayName("should return false and skip processing when the signature does not match")
    void shouldReturnFalseAndNotApply_whenSignatureMismatch() {
      // Given
      Map<String, Object> payload = new HashMap<>();
      payload.put("orderId", "REF1");
      payload.put("resultCode", 0);
      payload.put("signature", "not-the-real-signature");

      // When
      boolean result = momoService.handleWebhook(payload);

      // Then
      assertThat(result).isFalse();
      verify(purchaseRepository, never()).findByTransactionRef(any());
    }

    @Test
    @DisplayName("should mark the purchase COMPLETED and notify the author on a successful result")
    void shouldApplySuccessResult_whenSignatureValid() {
      // Given
      BookPurchaseEntity purchase =
          existingPurchase(PaymentStatus.PENDING, "REF1", OffsetDateTime.now());
      when(purchaseRepository.findByTransactionRef("REF1")).thenReturn(Optional.of(purchase));
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, "Book"));
      when(userRepository.findById(BUYER_ID))
          .thenReturn(Optional.of(userWithName(BUYER_ID, "Bob")));
      Map<String, Object> payload = validIpnPayload("REF1", 0, "TX123", "qr");

      // When
      boolean result = momoService.handleWebhook(payload);

      // Then
      assertThat(result).isTrue();
      assertThat(purchase.getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
      assertThat(purchase.getGatewayTransactionNo()).isEqualTo("TX123");
      assertThat(purchase.getPaymentMethod()).isEqualTo("qr");
      assertThat(purchase.getPaidAt()).isNotNull();
      verify(purchaseRepository).save(purchase);
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getBody()).isEqualTo("Bob purchased \"Book\"");
    }

    @Test
    @DisplayName("should mark the purchase FAILED and skip notification on a failed result")
    void shouldApplyFailedResult_whenSignatureValidButResultCodeNonZero() {
      // Given
      BookPurchaseEntity purchase =
          existingPurchase(PaymentStatus.PENDING, "REF1", OffsetDateTime.now());
      when(purchaseRepository.findByTransactionRef("REF1")).thenReturn(Optional.of(purchase));
      Map<String, Object> payload = validIpnPayload("REF1", 99, "", null);

      // When
      boolean result = momoService.handleWebhook(payload);

      // Then
      assertThat(result).isFalse();
      assertThat(purchase.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
      verify(purchaseRepository).save(purchase);
      verify(notificationService, never()).send(any());
    }

    @Test
    @DisplayName("should default the payment method to ATM when payType is absent")
    void shouldUseDefaultPaymentMethod_whenPayTypeIsNull() {
      // Given
      BookPurchaseEntity purchase =
          existingPurchase(PaymentStatus.PENDING, "REF1", OffsetDateTime.now());
      when(purchaseRepository.findByTransactionRef("REF1")).thenReturn(Optional.of(purchase));
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, "Book"));
      when(userRepository.findById(BUYER_ID)).thenReturn(Optional.empty());
      Map<String, Object> payload = validIpnPayload("REF1", 0, "TX1", null);

      // When
      momoService.handleWebhook(payload);

      // Then
      assertThat(purchase.getPaymentMethod()).isEqualTo("ATM");
    }

    @Test
    @DisplayName("should skip notifying when the buyer is the book's own author")
    void shouldSkipNotification_whenBuyerIsBookAuthor() {
      // Given
      BookPurchaseEntity purchase =
          existingPurchase(PaymentStatus.PENDING, "REF1", OffsetDateTime.now());
      when(purchaseRepository.findByTransactionRef("REF1")).thenReturn(Optional.of(purchase));
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, BUYER_ID, 1000L, "Book"));
      Map<String, Object> payload = validIpnPayload("REF1", 0, "TX1", "ATM");

      // When
      momoService.handleWebhook(payload);

      // Then
      assertThat(purchase.getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
      verify(notificationService, never()).send(any());
      verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("should short-circuit as already-applied without re-saving or re-notifying")
    void shouldReturnTrueWithoutReapplying_whenPurchaseAlreadyCompleted() {
      // Given
      BookPurchaseEntity purchase =
          existingPurchase(PaymentStatus.COMPLETED, "REF1", OffsetDateTime.now());
      when(purchaseRepository.findByTransactionRef("REF1")).thenReturn(Optional.of(purchase));
      Map<String, Object> payload = validIpnPayload("REF1", 0, "TX1", "ATM");

      // When
      boolean result = momoService.handleWebhook(payload);

      // Then
      assertThat(result).isTrue();
      verify(purchaseRepository, never()).save(any());
      verify(notificationService, never()).send(any());
      verify(bookService, never()).findBookOrThrow(any());
    }

    @Test
    @DisplayName("should fall back to \"Someone\" as the buyer name when it is blank")
    void shouldFallBackToSomeone_whenBuyerFullNameIsBlank() {
      // Given
      BookPurchaseEntity purchase =
          existingPurchase(PaymentStatus.PENDING, "REF1", OffsetDateTime.now());
      when(purchaseRepository.findByTransactionRef("REF1")).thenReturn(Optional.of(purchase));
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, "Book"));
      when(userRepository.findById(BUYER_ID))
          .thenReturn(Optional.of(userWithName(BUYER_ID, "   ")));
      Map<String, Object> payload = validIpnPayload("REF1", 0, "TX1", "ATM");

      // When
      momoService.handleWebhook(payload);

      // Then
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getBody()).startsWith("Someone purchased");
    }
  }

  // =====================================================================
  // syncPaymentStatus
  // =====================================================================

  @Nested
  @DisplayName("syncPaymentStatus")
  class SyncPaymentStatusTests {

    @Test
    @DisplayName("should reject when the transaction does not exist")
    void shouldThrowNotFoundException_whenTransactionDoesNotExist() {
      // Given
      when(purchaseRepository.findByTransactionRef("REF404")).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> momoService.syncPaymentStatus("REF404"))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Purchase not found");
    }

    @Test
    @DisplayName("should poll MoMo and apply a successful result")
    void shouldSyncSuccessResult_andReturnTrue() {
      // Given
      BookPurchaseEntity purchase =
          existingPurchase(PaymentStatus.PENDING, "REF1", OffsetDateTime.now());
      when(purchaseRepository.findByTransactionRef("REF1")).thenReturn(Optional.of(purchase));
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, "Book"));
      when(userRepository.findById(BUYER_ID))
          .thenReturn(Optional.of(userWithName(BUYER_ID, "Carol")));
      Map<String, Object> response = new HashMap<>();
      response.put("resultCode", 0);
      response.put("transId", "TX999");
      stubMomoResponse(Mono.just(response));

      // When
      boolean result = momoService.syncPaymentStatus("REF1");

      // Then
      assertThat(result).isTrue();
      assertThat(purchase.getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
      assertThat(requestBodyCaptor.getValue().get("orderId")).isEqualTo("REF1");
    }

    @Test
    @DisplayName("should reject when MoMo returns no response while polling")
    void shouldThrowValidationException_whenMomoReturnsNoResponse() {
      // Given
      BookPurchaseEntity purchase =
          existingPurchase(PaymentStatus.PENDING, "REF1", OffsetDateTime.now());
      when(purchaseRepository.findByTransactionRef("REF1")).thenReturn(Optional.of(purchase));
      stubMomoResponse(Mono.empty());

      // When / Then
      assertThatThrownBy(() -> momoService.syncPaymentStatus("REF1"))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("MoMo returned no response");
    }
  }

  private static UserEntity userWithName(Integer id, String fullName) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setFullName(fullName);
    return user;
  }
}
