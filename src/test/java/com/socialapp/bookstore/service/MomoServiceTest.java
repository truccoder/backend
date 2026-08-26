package com.socialapp.bookstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

/**
 * Component (unit) tests for {@link MomoService}, per ISTQB CTFL v4.0.1 (Section 2.2.1 component
 * testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3 BDD
 * Given/When/Then) — see {@code PostServiceTest} for the full rationale.
 *
 * <p>{@link MomoApiClient} (the MoMo protocol mechanics — signing, request shaping, the raw HTTP
 * call) is mocked here; its own behavior (link rejection, malformed responses, HMAC failures,
 * gateway outages, order-info truncation) is covered by {@link MomoApiClientTest} instead. This
 * class only tests {@code MomoService}'s own business logic: idempotency, the pending-payment
 * staleness window, and purchase state transitions.
 */
@ExtendWith(MockitoExtension.class)
class MomoServiceTest {

  private static final Integer BOOK_ID = 50;
  private static final Integer AUTHOR_ID = 1;
  private static final Integer BUYER_ID = 2;

  @Mock private MomoApiClient momoApiClient;
  @Mock private BookPurchaseRepository purchaseRepository;
  @Mock private BookService bookService;
  @Mock private UserRepository userRepository;
  @Mock private NotificationService notificationService;

  @Captor private ArgumentCaptor<BookPurchaseEntity> purchaseCaptor;
  @Captor private ArgumentCaptor<SendNotificationRequest> notificationCaptor;

  private MomoService momoService;

  @BeforeEach
  void setUp() {
    momoService =
        new MomoService(
            momoApiClient, purchaseRepository, bookService, userRepository, notificationService);
  }

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

  private static Map<String, Object> successResponse() {
    Map<String, Object> response = new HashMap<>();
    response.put("resultCode", 0);
    response.put("payUrl", "https://test-payment.momo.vn/pay/abc");
    response.put("qrCodeUrl", "https://test-payment.momo.vn/qr/abc");
    response.put("transId", "9876543210");
    return response;
  }

  private void stubSuccessfulLinkCreation() {
    when(momoApiClient.createOrderId()).thenReturn("NEW-ORDER-ID");
    when(momoApiClient.requestPaymentLink(
            anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(successResponse());
  }

  private static Map<String, Object> ipnPayload(
      String orderId, int resultCode, String transId, String payType) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("orderId", orderId);
    payload.put("resultCode", resultCode);
    if (transId != null) {
      payload.put("transId", transId);
    }
    if (payType != null) {
      payload.put("payType", payType);
    }
    return payload;
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
      stubSuccessfulLinkCreation();

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
      stubSuccessfulLinkCreation();

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
      stubSuccessfulLinkCreation();

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
      stubSuccessfulLinkCreation();

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
      stubSuccessfulLinkCreation();

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
      when(momoApiClient.verifyIpnSignature(anyMap())).thenReturn(false);
      Map<String, Object> payload = ipnPayload("REF1", 0, null, null);

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
      when(momoApiClient.verifyIpnSignature(anyMap())).thenReturn(true);
      BookPurchaseEntity purchase =
          existingPurchase(PaymentStatus.PENDING, "REF1", OffsetDateTime.now());
      when(purchaseRepository.findByTransactionRef("REF1")).thenReturn(Optional.of(purchase));
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, "Book"));
      when(userRepository.findById(BUYER_ID))
          .thenReturn(Optional.of(userWithName(BUYER_ID, "Bob")));
      Map<String, Object> payload = ipnPayload("REF1", 0, "TX123", "qr");

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
      when(momoApiClient.verifyIpnSignature(anyMap())).thenReturn(true);
      BookPurchaseEntity purchase =
          existingPurchase(PaymentStatus.PENDING, "REF1", OffsetDateTime.now());
      when(purchaseRepository.findByTransactionRef("REF1")).thenReturn(Optional.of(purchase));
      Map<String, Object> payload = ipnPayload("REF1", 99, "", null);

      // When
      boolean result = momoService.handleWebhook(payload);

      // Then
      assertThat(result).isFalse();
      assertThat(purchase.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
      verify(purchaseRepository).save(purchase);
      verify(notificationService, never()).send(any());
    }

    @Test
    @DisplayName("should default the payment method to MOMO when payType is absent")
    void shouldUseDefaultPaymentMethod_whenPayTypeIsNull() {
      // Given
      when(momoApiClient.verifyIpnSignature(anyMap())).thenReturn(true);
      BookPurchaseEntity purchase =
          existingPurchase(PaymentStatus.PENDING, "REF1", OffsetDateTime.now());
      when(purchaseRepository.findByTransactionRef("REF1")).thenReturn(Optional.of(purchase));
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, "Book"));
      when(userRepository.findById(BUYER_ID)).thenReturn(Optional.empty());
      Map<String, Object> payload = ipnPayload("REF1", 0, "TX1", null);

      // When
      momoService.handleWebhook(payload);

      // Then
      // "ATM" until the request type moved from payWithATM to captureWallet — nothing in the
      // wallet flow is a card payment, so an absent payType must not record one.
      assertThat(purchase.getPaymentMethod()).isEqualTo("MOMO");
    }

    @Test
    @DisplayName("should skip notifying when the buyer is the book's own author")
    void shouldSkipNotification_whenBuyerIsBookAuthor() {
      // Given
      when(momoApiClient.verifyIpnSignature(anyMap())).thenReturn(true);
      BookPurchaseEntity purchase =
          existingPurchase(PaymentStatus.PENDING, "REF1", OffsetDateTime.now());
      when(purchaseRepository.findByTransactionRef("REF1")).thenReturn(Optional.of(purchase));
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, BUYER_ID, 1000L, "Book"));
      Map<String, Object> payload = ipnPayload("REF1", 0, "TX1", "ATM");

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
      when(momoApiClient.verifyIpnSignature(anyMap())).thenReturn(true);
      BookPurchaseEntity purchase =
          existingPurchase(PaymentStatus.COMPLETED, "REF1", OffsetDateTime.now());
      when(purchaseRepository.findByTransactionRef("REF1")).thenReturn(Optional.of(purchase));
      Map<String, Object> payload = ipnPayload("REF1", 0, "TX1", "ATM");

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
      when(momoApiClient.verifyIpnSignature(anyMap())).thenReturn(true);
      BookPurchaseEntity purchase =
          existingPurchase(PaymentStatus.PENDING, "REF1", OffsetDateTime.now());
      when(purchaseRepository.findByTransactionRef("REF1")).thenReturn(Optional.of(purchase));
      when(bookService.findBookOrThrow(BOOK_ID))
          .thenReturn(paidBook(BOOK_ID, AUTHOR_ID, 1000L, "Book"));
      when(userRepository.findById(BUYER_ID))
          .thenReturn(Optional.of(userWithName(BUYER_ID, "   ")));
      Map<String, Object> payload = ipnPayload("REF1", 0, "TX1", "ATM");

      // When
      momoService.handleWebhook(payload);

      // Then
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getBody()).startsWith("Someone purchased");
    }

    @Test
    @DisplayName("should return false without throwing when resultCode is missing")
    void shouldReturnFalse_whenResultCodeIsMissing() {
      // Given — MoMo's IPN payload omits resultCode entirely (a genuinely malformed callback)
      when(momoApiClient.verifyIpnSignature(anyMap())).thenReturn(true);
      Map<String, Object> payload = new HashMap<>();
      payload.put("orderId", "REF1");

      // When / Then — must never crash the webhook endpoint with a raw NumberFormatException
      boolean result = momoService.handleWebhook(payload);
      assertThat(result).isFalse();
      verify(purchaseRepository, never()).findByTransactionRef(any());
    }

    @Test
    @DisplayName("should return false without throwing when resultCode is non-numeric text")
    void shouldReturnFalse_whenResultCodeIsNonNumericText() {
      // Given
      when(momoApiClient.verifyIpnSignature(anyMap())).thenReturn(true);
      Map<String, Object> payload = new HashMap<>();
      payload.put("orderId", "REF1");
      payload.put("resultCode", "not-a-number");

      // When / Then
      boolean result = momoService.handleWebhook(payload);
      assertThat(result).isFalse();
      verify(purchaseRepository, never()).findByTransactionRef(any());
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
      assertThatThrownBy(() -> momoService.syncPaymentStatus(BUYER_ID, "REF404"))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Purchase not found");
    }

    @Test
    @DisplayName("should refuse to sync a purchase belonging to somebody else")
    void shouldRefuse_whenCallerIsNotTheBuyer() {
      // Given: a purchase owned by BUYER_ID. The ref used to be trusted on its own, so any
      // signed-in user could drive somebody else's order to COMPLETED or FAILED and fire the
      // author's "your book sold" notification with it.
      when(purchaseRepository.findByTransactionRef("REF1"))
          .thenReturn(
              Optional.of(existingPurchase(PaymentStatus.PENDING, "REF1", OffsetDateTime.now())));

      // When / Then: 404, not 403 — somebody else's order ref is not theirs to confirm exists
      assertThatThrownBy(() -> momoService.syncPaymentStatus(BUYER_ID + 1, "REF1"))
          .isInstanceOf(NotFoundException.class);
      verifyNoInteractions(momoApiClient);
    }

    @Test
    @DisplayName("should return false when MoMo answers with a malformed resultCode")
    void shouldReturnFalse_whenResultCodeIsMalformed() {
      // Given: handleWebhook already guarded this; the query path did not, so a junk resultCode
      // threw NumberFormatException out of a user-facing endpoint.
      when(purchaseRepository.findByTransactionRef("REF1"))
          .thenReturn(
              Optional.of(existingPurchase(PaymentStatus.PENDING, "REF1", OffsetDateTime.now())));
      Map<String, Object> response = new HashMap<>();
      response.put("resultCode", "not-a-number");
      when(momoApiClient.queryPaymentStatus("REF1")).thenReturn(response);

      // When
      boolean result = momoService.syncPaymentStatus(BUYER_ID, "REF1");

      // Then
      assertThat(result).isFalse();
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
      when(momoApiClient.queryPaymentStatus("REF1")).thenReturn(response);

      // When
      boolean result = momoService.syncPaymentStatus(BUYER_ID, "REF1");

      // Then
      assertThat(result).isTrue();
      assertThat(purchase.getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @ParameterizedTest(name = "resultCode {0} leaves the purchase PENDING")
    @ValueSource(ints = {1000, 7000, 7002})
    @DisplayName("should not fail a purchase MoMo is still processing")
    void shouldLeavePending_whenResultCodeIsNotFinal(int resultCode) {
      // Given: the state `/payment/success` most often finds on its FIRST poll — the browser is
      // back from MoMo before MoMo has settled. 1000 is "awaiting the user's confirmation",
      // 7000/7002 are "being processed by the payment provider". None of them is a failure.
      BookPurchaseEntity purchase =
          existingPurchase(PaymentStatus.PENDING, "REF1", OffsetDateTime.now());
      when(purchaseRepository.findByTransactionRef("REF1")).thenReturn(Optional.of(purchase));
      Map<String, Object> response = new HashMap<>();
      response.put("resultCode", resultCode);
      when(momoApiClient.queryPaymentStatus("REF1")).thenReturn(response);

      // When
      boolean result = momoService.syncPaymentStatus(BUYER_ID, "REF1");

      // Then: still unpaid, but NOT written off. This used to land in the `else` branch and mark
      // the row FAILED, so a healthy payment was destroyed by the very poll meant to confirm it —
      // and the money would arrive against a row saying it had not been paid.
      assertThat(result).isFalse();
      assertThat(purchase.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
      verify(notificationService, never()).send(any());
    }
  }

  private static UserEntity userWithName(Integer id, String fullName) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setFullName(fullName);
    return user;
  }
}
