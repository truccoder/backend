package com.socialapp.bookstore.service;

import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.bookstore.dto.PaymentResponseDto;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.entity.BookPurchaseEntity;
import com.socialapp.bookstore.entity.enums.PaymentStatus;
import com.socialapp.bookstore.repository.BookPurchaseRepository;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Business orchestration for MoMo "AIO v2" payments — idempotency, purchase state transitions,
 * and author notification. See {@link MomoApiClient} for the MoMo protocol mechanics (signing,
 * request shaping, the raw HTTP call) this class delegates to.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MomoService {
  private final MomoApiClient momoApiClient;
  private final BookPurchaseRepository purchaseRepository;
  private final BookService bookService;
  private final UserRepository userRepository;
  private final NotificationService notificationService;

  private static final String DEFAULT_PAYMENT_METHOD = "ATM";
  private static final int SUCCESS_RESULT_CODE = 0;
  // MoMo's hosted payWithATM link is short-lived; matches the window we're willing to assume a
  // pending transactionRef might still be paid against before treating it as abandoned.
  private static final long PENDING_PAYMENT_STALE_MINUTES = 15;

  @Transactional
  public PaymentResponseDto createPayment(Integer buyerId, Integer bookId) {
    BookEntity book = bookService.findBookOrThrow(bookId);

    if (book.getIsFree()) {
      throw new ValidationException("This book is free, no payment required");
    }

    if (book.getAuthorId().equals(buyerId)) {
      throw new ValidationException("Cannot purchase your own book");
    }

    // (book_id, buyer_id) is unique, so a purchase row is reused across retries rather than
    // inserted again. transaction_ref is also globally UNIQUE, so we cannot simply overwrite it
    // with a new orderId while the old one might still be actively payable — the old orderId
    // would become unreconcilable (any MoMo IPN/sync against it would 404). Only overwrite once
    // the previous pending attempt is old enough to safely assume it was abandoned.
    BookPurchaseEntity purchase =
        purchaseRepository.findByBookIdAndBuyerId(bookId, buyerId).orElse(null);
    if (purchase != null && purchase.getPaymentStatus() == PaymentStatus.COMPLETED) {
      throw new ValidationException("You have already purchased this book");
    }
    if (purchase != null
        && purchase.getPaymentStatus() == PaymentStatus.PENDING
        && purchase.getTransactionRef() != null
        && purchase.getUpdatedAt() != null
        && purchase
            .getUpdatedAt()
            .isAfter(OffsetDateTime.now().minusMinutes(PENDING_PAYMENT_STALE_MINUTES))) {
      throw new ValidationException(
          "A payment for this book is already in progress (orderId="
              + purchase.getTransactionRef()
              + "). Please complete it, or try again in a few minutes if it expired.");
    }

    String orderId = momoApiClient.createOrderId();
    String requestId = orderId;
    String amount = String.valueOf(book.getPrice());
    String orderInfo = "Mua sach: " + book.getTitle();
    String extraData = "";

    if (purchase == null) {
      purchase = BookPurchaseEntity.builder().bookId(bookId).buyerId(buyerId).build();
    }
    purchase.setAmount(book.getPrice());
    purchase.setTransactionRef(orderId);
    purchase.setPaymentStatus(PaymentStatus.PENDING);
    purchase.setGatewayTransactionNo(null);
    purchase.setPaidAt(null);
    purchaseRepository.save(purchase);
    log.info(
        "[bookId={}, buyerId={}] createPayment: saved purchase as PENDING, orderId={}",
        bookId,
        buyerId,
        orderId);

    Map<String, Object> data =
        momoApiClient.requestPaymentLink(orderId, requestId, amount, orderInfo, extraData);

    return PaymentResponseDto.builder()
        .paymentUrl((String) data.get("payUrl"))
        .transactionRef(orderId)
        .qrCode((String) data.get("qrCodeUrl"))
        .build();
  }

  /**
   * Server-to-server IPN MoMo calls when a payment's status changes.
   */
  @Transactional
  public boolean handleWebhook(Map<String, Object> payload) {
    String orderId = String.valueOf(payload.get("orderId"));

    if (!momoApiClient.verifyIpnSignature(payload)) {
      log.warn("[orderId={}] MoMo IPN signature mismatch, ignoring payload", orderId);
      return false;
    }

    int resultCode;
    try {
      resultCode = asInt(payload.get("resultCode"));
    } catch (NumberFormatException e) {
      log.warn(
          "[orderId={}] MoMo IPN payload has a malformed resultCode, ignoring payload", orderId);
      return false;
    }

    return applyResult(
        orderId,
        resultCode,
        String.valueOf(payload.getOrDefault("transId", "")),
        (String) payload.get("payType"));
  }

  /**
   * Polls MoMo directly for a transaction's current status and syncs it locally. Useful in local
   * development, where MoMo cannot reach an IPN URL on localhost.
   */
  @Transactional
  public boolean syncPaymentStatus(String transactionRef) {
    findPurchaseOrThrow(transactionRef);

    Map<String, Object> response = momoApiClient.queryPaymentStatus(transactionRef);

    log.info(
        "[orderId={}] syncPaymentStatus: MoMo resultCode={}",
        transactionRef,
        response.get("resultCode"));

    return applyResult(
        transactionRef,
        asInt(response.get("resultCode")),
        String.valueOf(response.getOrDefault("transId", "")),
        null);
  }

  private boolean applyResult(String orderId, int resultCode, String transId, String payType) {
    BookPurchaseEntity purchase = findPurchaseOrThrow(orderId);
    boolean success = resultCode == SUCCESS_RESULT_CODE;

    log.info("[orderId={}] applyResult: resultCode={}, transId={}", orderId, resultCode, transId);

    // Idempotency: MoMo IPN can retry, and syncPaymentStatus() can be called repeatedly. If this
    // purchase was already completed, don't re-set the fields or re-notify the author again.
    if (purchase.getPaymentStatus() == PaymentStatus.COMPLETED) {
      log.info("[orderId={}] applyResult: already COMPLETED, ignoring duplicate callback", orderId);
      return true;
    }

    if (success) {
      purchase.setPaymentStatus(PaymentStatus.COMPLETED);
      purchase.setGatewayTransactionNo(transId);
      purchase.setPaymentMethod(payType != null ? payType : DEFAULT_PAYMENT_METHOD);
      purchase.setPaidAt(OffsetDateTime.now());
    } else {
      purchase.setPaymentStatus(PaymentStatus.FAILED);
    }

    purchaseRepository.save(purchase);
    if (success) {
      notifyBookAuthor(purchase);
    }
    return success;
  }

  private void notifyBookAuthor(BookPurchaseEntity purchase) {
    BookEntity book = bookService.findBookOrThrow(purchase.getBookId());
    if (book.getAuthorId().equals(purchase.getBuyerId())) {
      return;
    }
    notificationService.send(
        SendNotificationRequest.builder()
            .recipientId(book.getAuthorId())
            .actorId(purchase.getBuyerId())
            .type(NotificationType.BOOK_PURCHASED)
            .title("Your book was purchased")
            .body(buyerName(purchase.getBuyerId()) + " purchased \"" + book.getTitle() + "\"")
            .referenceId(book.getId())
            .referenceType("BOOK")
            .build());
  }

  private String buyerName(Integer userId) {
    return userRepository
        .findById(userId)
        .map(UserEntity::getFullName)
        .filter(name -> !name.isBlank())
        .orElse("Someone");
  }

  private int asInt(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    return Integer.parseInt(String.valueOf(value));
  }

  private BookPurchaseEntity findPurchaseOrThrow(String transactionRef) {
    return purchaseRepository
        .findByTransactionRef(transactionRef)
        .orElseThrow(() -> new NotFoundException("Purchase not found: " + transactionRef));
  }
}
