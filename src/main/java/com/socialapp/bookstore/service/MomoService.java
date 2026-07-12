package com.socialapp.bookstore.service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import com.socialapp.bookstore.config.MomoProperties;
import com.socialapp.bookstore.dto.PaymentResponseDto;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.entity.BookPurchaseEntity;
import com.socialapp.bookstore.entity.enums.PaymentStatus;
import com.socialapp.bookstore.repository.BookPurchaseRepository;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.PaymentException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;

/**
 * Integrates MoMo's "AIO v2" payment gateway using the payWithATM request type — the hosted
 * payUrl page has the buyer pick a bank and type in their domestic ATM card details directly,
 * rather than scanning a QR code with the MoMo app (captureWallet). See
 * https://developers.momo.vn/v3/docs/payment/api/wallet/onetimepayment and the official sample at
 * https://github.com/momo-wallet/payment/blob/master/nodejs/pay-with-ATM.js, which is what the
 * exact field names/order below (both request bodies and the HMAC-SHA256 rawSignature strings)
 * are taken from — the request/signature shape is identical across requestTypes, so switching
 * back only means changing REQUEST_TYPE.
 */
@Slf4j
@Service
public class MomoService {
  private final MomoProperties momoProperties;
  private final BookPurchaseRepository purchaseRepository;
  private final BookService bookService;
  private final WebClient momoWebClient;
  private final UserRepository userRepository;
  private final NotificationService notificationService;

  private static final String REQUEST_TYPE = "payWithATM";
  private static final String DEFAULT_PAYMENT_METHOD = "ATM";
  private static final int SUCCESS_RESULT_CODE = 0;
  // MoMo's hosted payWithATM link is short-lived; matches the window we're willing to assume a
  // pending transactionRef might still be paid against before treating it as abandoned.
  private static final long PENDING_PAYMENT_STALE_MINUTES = 15;

  public MomoService(
      MomoProperties momoProperties,
      BookPurchaseRepository purchaseRepository,
      BookService bookService,
      @Qualifier("momoWebClient") WebClient momoWebClient,
      UserRepository userRepository,
      NotificationService notificationService) {
    this.momoProperties = momoProperties;
    this.purchaseRepository = purchaseRepository;
    this.bookService = bookService;
    this.momoWebClient = momoWebClient;
    this.userRepository = userRepository;
    this.notificationService = notificationService;
  }

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

    String orderId = momoProperties.getPartnerCode() + System.currentTimeMillis();
    String requestId = orderId;
    String amount = String.valueOf(book.getPrice());
    String orderInfo = truncateOrderInfo("Mua sach: " + book.getTitle());
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

    Map<String, Object> data = requestPaymentLink(orderId, requestId, amount, orderInfo, extraData);

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
    String computedSignature = buildIpnSignature(payload);
    String receivedSignature = String.valueOf(payload.get("signature"));

    if (!computedSignature.equalsIgnoreCase(receivedSignature)) {
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

    String requestId = transactionRef;
    String rawSignature =
        "accessKey="
            + momoProperties.getAccessKey()
            + "&orderId="
            + transactionRef
            + "&partnerCode="
            + momoProperties.getPartnerCode()
            + "&requestId="
            + requestId;

    Map<String, Object> requestBody =
        Map.of(
            "partnerCode",
            momoProperties.getPartnerCode(),
            "accessKey",
            momoProperties.getAccessKey(),
            "requestId",
            requestId,
            "orderId",
            transactionRef,
            "lang",
            "vi",
            "signature",
            hmacSHA256(momoProperties.getSecretKey(), rawSignature));

    Map<String, Object> response = postForMap("/v2/gateway/api/query", requestBody);

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

  private Map<String, Object> requestPaymentLink(
      String orderId, String requestId, String amount, String orderInfo, String extraData) {
    String rawSignature =
        "accessKey="
            + momoProperties.getAccessKey()
            + "&amount="
            + amount
            + "&extraData="
            + extraData
            + "&ipnUrl="
            + momoProperties.getIpnUrl()
            + "&orderId="
            + orderId
            + "&orderInfo="
            + orderInfo
            + "&partnerCode="
            + momoProperties.getPartnerCode()
            + "&redirectUrl="
            + momoProperties.getRedirectUrl()
            + "&requestId="
            + requestId
            + "&requestType="
            + REQUEST_TYPE;
    String signature = hmacSHA256(momoProperties.getSecretKey(), rawSignature);

    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("partnerCode", momoProperties.getPartnerCode());
    requestBody.put("accessKey", momoProperties.getAccessKey());
    requestBody.put("requestId", requestId);
    requestBody.put("amount", amount);
    requestBody.put("orderId", orderId);
    requestBody.put("orderInfo", orderInfo);
    requestBody.put("redirectUrl", momoProperties.getRedirectUrl());
    requestBody.put("ipnUrl", momoProperties.getIpnUrl());
    requestBody.put("extraData", extraData);
    requestBody.put("requestType", REQUEST_TYPE);
    requestBody.put("signature", signature);
    requestBody.put("lang", "vi");

    Map<String, Object> response = postForMap("/v2/gateway/api/create", requestBody);

    if (asInt(response.get("resultCode")) != SUCCESS_RESULT_CODE) {
      log.error("[orderId={}] MoMo create-payment failed: {}", orderId, response);
      throw new ValidationException("Failed to create MoMo payment link");
    }

    return response;
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

  private String buildIpnSignature(Map<String, Object> payload) {
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

  private int asInt(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    return Integer.parseInt(String.valueOf(value));
  }

  private String truncateOrderInfo(String orderInfo) {
    int maxLength = 100;
    return orderInfo.length() <= maxLength ? orderInfo : orderInfo.substring(0, maxLength);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> postForMap(String uri, Map<String, Object> requestBody) {
    Map<String, Object> response;
    try {
      response =
          momoWebClient
              .post()
              .uri(uri)
              .bodyValue(requestBody)
              .retrieve()
              .bodyToMono(Map.class)
              .block();
    } catch (Exception e) {
      throw new PaymentException("Failed to reach MoMo for " + uri, e);
    }

    if (Objects.isNull(response)) {
      throw new ValidationException("MoMo returned no response for " + uri);
    }
    return response;
  }

  private String hmacSHA256(String key, String data) {
    try {
      Mac hmac = Mac.getInstance("HmacSHA256");
      SecretKeySpec secretKey =
          new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
      hmac.init(secretKey);
      byte[] hash = hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new PaymentException("Failed to generate HMAC", e);
    }
  }

  private BookPurchaseEntity findPurchaseOrThrow(String transactionRef) {
    return purchaseRepository
        .findByTransactionRef(transactionRef)
        .orElseThrow(() -> new NotFoundException("Purchase not found: " + transactionRef));
  }
}
