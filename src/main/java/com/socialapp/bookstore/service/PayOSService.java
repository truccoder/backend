package com.socialapp.bookstore.service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.bookstore.config.PayOSProperties;
import com.socialapp.bookstore.dto.PaymentResponseDto;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.entity.BookPurchaseEntity;
import com.socialapp.bookstore.entity.enums.PaymentStatus;
import com.socialapp.bookstore.repository.BookPurchaseRepository;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class PayOSService {
  private final PayOSProperties payOSProperties;
  private final BookPurchaseRepository purchaseRepository;
  private final BookService bookService;
  private final ObjectMapper objectMapper;
  private final WebClient payOSWebClient;

  private static final String PAYMENT_METHOD = "PAYOS_BANK_TRANSFER";
  private static final int MAX_DESCRIPTION_LENGTH = 25;

  public PayOSService(
      PayOSProperties payOSProperties,
      BookPurchaseRepository purchaseRepository,
      BookService bookService,
      ObjectMapper objectMapper,
      @Qualifier("payOSWebClient") WebClient payOSWebClient) {
    this.payOSProperties = payOSProperties;
    this.purchaseRepository = purchaseRepository;
    this.bookService = bookService;
    this.objectMapper = objectMapper;
    this.payOSWebClient = payOSWebClient;
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

    boolean alreadyPurchased =
        purchaseRepository.existsByBookIdAndBuyerIdAndPaymentStatus(
            bookId, buyerId, PaymentStatus.COMPLETED);
    if (alreadyPurchased) {
      throw new ValidationException("You have already purchased this book");
    }

    Long orderCode = generateOrderCode();
    String transactionRef = String.valueOf(orderCode);

    BookPurchaseEntity purchase =
        BookPurchaseEntity.builder()
            .bookId(bookId)
            .buyerId(buyerId)
            .amount(book.getPrice())
            .transactionRef(transactionRef)
            .paymentStatus(PaymentStatus.PENDING)
            .build();

    purchaseRepository.save(purchase);
    log.info(
        "[bookId={}, buyerId={}] createPayment: saved purchase as PENDING, orderCode={}",
        bookId,
        buyerId,
        orderCode);

    Map<String, Object> data = createPaymentLink(orderCode, book.getPrice(), book.getTitle());

    purchase.setPaymentLinkId((String) data.get("paymentLinkId"));
    purchaseRepository.save(purchase);

    log.info(
        "[bookId={}, buyerId={}] createPayment: PayOS link created, paymentLinkId={}",
        bookId,
        buyerId,
        data.get("paymentLinkId"));

    return PaymentResponseDto.builder()
        .paymentUrl((String) data.get("checkoutUrl"))
        .transactionRef(transactionRef)
        .qrCode((String) data.get("qrCode"))
        .build();
  }

  /** Server-to-server webhook PayOS calls when a payment's status changes. */
  @Transactional
  @SuppressWarnings("unchecked")
  public boolean handleWebhook(Map<String, Object> payload) {
    Object dataObj = payload.get("data");
    Object signature = payload.get("signature");

    if (!(dataObj instanceof Map) || signature == null) {
      log.warn("PayOS webhook payload missing data/signature");
      return false;
    }

    Map<String, Object> data = (Map<String, Object>) dataObj;
    String computedSignature = createSignatureFromMap(data, payOSProperties.getChecksumKey());
    if (!computedSignature.equalsIgnoreCase(String.valueOf(signature))) {
      log.warn(
          "[orderCode={}] PayOS webhook signature mismatch, ignoring payload",
          data.get("orderCode"));
      return false;
    }

    return applyPaymentResult(data);
  }

  /**
   * Polls PayOS directly for a payment's current status and syncs it locally. Useful in local
   * development, where PayOS cannot reach a webhook URL on localhost.
   */
  @Transactional
  public boolean syncPaymentStatus(String transactionRef) {
    BookPurchaseEntity purchase = findPurchaseOrThrow(transactionRef);

    @SuppressWarnings("unchecked")
    Map<String, Object> response =
        payOSWebClient
            .get()
            .uri("/v2/payment-requests/{orderCode}", transactionRef)
            .retrieve()
            .bodyToMono(Map.class)
            .block();

    if (Objects.isNull(response)) {
      throw new ValidationException("PayOS returned no response for orderCode " + transactionRef);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) response.get("data");
    if (Objects.isNull(data)) {
      throw new ValidationException("PayOS response missing data for orderCode " + transactionRef);
    }

    String status = (String) data.get("status");
    log.info("[orderCode={}] syncPaymentStatus: PayOS status={}", transactionRef, status);

    return applyStatus(purchase, status, null);
  }

  private boolean applyPaymentResult(Map<String, Object> data) {
    String transactionRef = String.valueOf(((Number) data.get("orderCode")).longValue());
    BookPurchaseEntity purchase = findPurchaseOrThrow(transactionRef);

    String code = String.valueOf(data.get("code"));
    boolean success = "00".equals(code);
    String reference = (String) data.get("reference");

    log.info(
        "[orderCode={}] applyPaymentResult: code={}, reference={}",
        transactionRef,
        code,
        reference);

    if (success) {
      purchase.setPaymentStatus(PaymentStatus.COMPLETED);
      purchase.setGatewayTransactionNo(reference);
      purchase.setPaymentMethod(PAYMENT_METHOD);
      purchase.setPaidAt(OffsetDateTime.now());
    } else {
      purchase.setPaymentStatus(PaymentStatus.FAILED);
    }

    purchaseRepository.save(purchase);
    return success;
  }

  private boolean applyStatus(BookPurchaseEntity purchase, String status, String reference) {
    boolean success = "PAID".equalsIgnoreCase(status);

    if (success) {
      purchase.setPaymentStatus(PaymentStatus.COMPLETED);
      purchase.setGatewayTransactionNo(reference);
      purchase.setPaymentMethod(PAYMENT_METHOD);
      purchase.setPaidAt(OffsetDateTime.now());
    } else if ("CANCELLED".equalsIgnoreCase(status) || "EXPIRED".equalsIgnoreCase(status)) {
      purchase.setPaymentStatus(PaymentStatus.FAILED);
    }

    purchaseRepository.save(purchase);
    return success;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> createPaymentLink(Long orderCode, Long amount, String bookTitle) {
    String description = truncateDescription("Mua sach: " + bookTitle);

    String signatureData =
        "amount="
            + amount
            + "&cancelUrl="
            + payOSProperties.getCancelUrl()
            + "&description="
            + description
            + "&orderCode="
            + orderCode
            + "&returnUrl="
            + payOSProperties.getReturnUrl();
    String signature = hmacSHA256(payOSProperties.getChecksumKey(), signatureData);

    Map<String, Object> requestBody =
        Map.of(
            "orderCode", orderCode,
            "amount", amount,
            "description", description,
            "cancelUrl", payOSProperties.getCancelUrl(),
            "returnUrl", payOSProperties.getReturnUrl(),
            "signature", signature);

    Map<String, Object> response =
        payOSWebClient
            .post()
            .uri("/v2/payment-requests")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(Map.class)
            .block();

    if (Objects.isNull(response) || !"00".equals(String.valueOf(response.get("code")))) {
      log.error("[orderCode={}] PayOS create-payment-link failed: {}", orderCode, response);
      throw new ValidationException("Failed to create PayOS payment link");
    }

    return (Map<String, Object>) response.get("data");
  }

  private String truncateDescription(String description) {
    if (description.length() <= MAX_DESCRIPTION_LENGTH) {
      return description;
    }
    return description.substring(0, MAX_DESCRIPTION_LENGTH);
  }

  private String createSignatureFromMap(Map<String, Object> data, String checksumKey) {
    TreeMap<String, Object> sorted = new TreeMap<>(data);
    String dataString =
        sorted.entrySet().stream()
            .map(e -> e.getKey() + "=" + stringifyValue(e.getValue()))
            .collect(Collectors.joining("&"));
    return hmacSHA256(checksumKey, dataString);
  }

  private String stringifyValue(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof Map || value instanceof List) {
      try {
        return objectMapper.writeValueAsString(value);
      } catch (Exception e) {
        return String.valueOf(value);
      }
    }
    return String.valueOf(value);
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
      throw new RuntimeException("Failed to generate HMAC", e);
    }
  }

  private Long generateOrderCode() {
    return System.currentTimeMillis() * 1000 + ThreadLocalRandom.current().nextInt(1000);
  }

  private BookPurchaseEntity findPurchaseOrThrow(String transactionRef) {
    return purchaseRepository
        .findByTransactionRef(transactionRef)
        .orElseThrow(() -> new NotFoundException("Purchase not found: " + transactionRef));
  }
}
