package com.socialapp.bookstore.service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

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

  /**
   * Fallback label for {@code payment_method} when MoMo's answer carries no {@code payType}.
   *
   * <p>Was {@code "ATM"}, which stopped being true when {@link MomoApiClient} moved to
   * {@code captureWallet}: nothing about that flow is a card payment, so an absent {@code payType}
   * would have recorded every wallet purchase as an ATM one. The gateway's own name is the honest
   * answer when the gateway declines to be more specific.
   */
  private static final String DEFAULT_PAYMENT_METHOD = "MOMO";

  private static final int SUCCESS_RESULT_CODE = 0;
  // How long a pending transactionRef is assumed to still be payable before it is treated as
  // abandoned and overwritten. Kept in step with MomoProperties.orderExpireMinutes, which is what
  // MoMo is told; an order that outlives this window is one a customer can still pay after this
  // side has forgotten the reference.
  //
  // IT IS ALSO HOW LONG A BUYER IS LOCKED OUT OF RETRYING, which is why the rejection below names
  // the orderId and says when to come back rather than failing blankly.
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

    // A signed IPN naming an orderId this database has no row for means money may have moved with
    // nothing on this side to attach it to — the one failure in this flow that loses evidence
    // rather than just an update. Logged at ERROR with the whole payload before the exception
    // leaves, because that payload carries MoMo's own signature and is the only record of the
    // transaction we will ever hold. Rethrown unchanged so the caller still sees a 404 and MoMo
    // still retries; the point is that the retries are now visible.
    if (!purchaseRepository.findByTransactionRef(orderId).isPresent()) {
      log.error(
          "[orderId={}] MoMo IPN passed signature verification but no purchase holds this"
              + " transaction ref. If resultCode is 0 this is a PAID order with no local record —"
              + " reconcile by hand. Signed payload: {}",
          orderId,
          payload);
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
  public boolean syncPaymentStatus(Integer callerId, String transactionRef) {
    BookPurchaseEntity purchase = findPurchaseOrThrow(transactionRef);

    // The transaction ref comes straight off the path and used to be trusted on its own, so any
    // signed-in user could drive somebody else's purchase to COMPLETED or FAILED — and trigger the
    // author's "your book sold" notification with it. 404, not 403: someone else's order ref is
    // not theirs to confirm the existence of.
    if (!purchase.getBuyerId().equals(callerId)) {
      throw new NotFoundException("Purchase not found: " + transactionRef);
    }

    Map<String, Object> response = momoApiClient.queryPaymentStatus(transactionRef);

    log.info(
        "[orderId={}] syncPaymentStatus: MoMo resultCode={}",
        transactionRef,
        response.get("resultCode"));

    // Same guard as handleWebhook: a malformed resultCode from MoMo is their bad data, not a
    // reason to throw NumberFormatException out of a user-facing endpoint.
    int resultCode;
    try {
      resultCode = asInt(response.get("resultCode"));
    } catch (NumberFormatException e) {
      log.warn("[orderId={}] MoMo query returned a malformed resultCode", transactionRef);
      return false;
    }

    return applyResult(
        transactionRef, resultCode, String.valueOf(response.getOrDefault("transId", "")), null);
  }

  /**
   * MoMo result codes that mean "not finished yet", as opposed to "finished and failed".
   *
   * <p>{@code 1000} is "Giao dịch đã được khởi tạo, chờ người dùng xác nhận thanh toán" and
   * {@code 7000}/{@code 7002} are "đang được xử lý" — a payment still in flight, which is the most
   * likely thing MoMo has to say at the exact moment this is asked.
   */
  private static final Set<Integer> IN_FLIGHT_RESULT_CODES = Set.of(1000, 7000, 7002);

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

    /**
     * NOT SUCCESS IS NOT THE SAME AS FAILED, and treating it as such destroyed live payments.
     *
     * <p>This method used to be `success ? COMPLETED : FAILED` on a single equality test against 0.
     * Every other code MoMo can answer with went to FAILED — including the ones that mean the
     * payment is still running. `/payment/success` polls `syncPaymentStatus` on arrival precisely
     * because the browser usually beats the settlement, so the FIRST poll of a perfectly healthy
     * purchase would routinely read 1000 or 7002 and write FAILED over a payment that then went on
     * to succeed. The money arrives against a row that says it did not.
     *
     * <p>Leaving the row PENDING and returning false is what the caller already expects: the panel
     * keeps polling on its bounded interval, and the IPN — or the next poll — settles it for real.
     */
    if (!success && IN_FLIGHT_RESULT_CODES.contains(resultCode)) {
      log.info(
          "[orderId={}] applyResult: resultCode={} is not final, leaving purchase PENDING",
          orderId,
          resultCode);
      return false;
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
