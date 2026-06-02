package com.socialapp.bookstore.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.bookstore.config.VNPayProperties;
import com.socialapp.bookstore.dto.PaymentResponseDto;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.entity.BookPurchaseEntity;
import com.socialapp.bookstore.entity.enums.PaymentStatus;
import com.socialapp.bookstore.repository.BookPurchaseRepository;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class VNPayService {
  private final VNPayProperties vnPayProperties;
  private final BookPurchaseRepository purchaseRepository;
  private final BookService bookService;

  private static final DateTimeFormatter VN_DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  @Transactional
  public PaymentResponseDto createPayment(Integer buyerId, Integer bookId, String ipAddress) {
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

    String transactionRef = generateTransactionRef();

    BookPurchaseEntity purchase =
        BookPurchaseEntity.builder()
            .bookId(bookId)
            .buyerId(buyerId)
            .amount(book.getPrice())
            .transactionRef(transactionRef)
            .paymentStatus(PaymentStatus.PENDING)
            .build();

    purchaseRepository.save(purchase);

    String paymentUrl =
        buildPaymentUrl(transactionRef, book.getPrice(), book.getTitle(), ipAddress);

    return PaymentResponseDto.builder()
        .paymentUrl(paymentUrl)
        .transactionRef(transactionRef)
        .build();
  }

  @Transactional
  public boolean handleCallback(Map<String, String> params) {
    String vnpSecureHash = params.get("vnp_SecureHash");
    Map<String, String> fields = new TreeMap<>(params);
    fields.remove("vnp_SecureHash");
    fields.remove("vnp_SecureHashType");

    String computedHash = hmacSHA512(vnPayProperties.getHashSecret(), buildHashData(fields));
    if (!computedHash.equalsIgnoreCase(vnpSecureHash)) {
      log.warn("VNPay callback signature mismatch");
      return false;
    }

    String transactionRef = params.get("vnp_TxnRef");
    String responseCode = params.get("vnp_ResponseCode");
    String transactionNo = params.get("vnp_TransactionNo");

    BookPurchaseEntity purchase =
        purchaseRepository
            .findByTransactionRef(transactionRef)
            .orElseThrow(() -> new NotFoundException("Purchase not found: " + transactionRef));

    if ("00".equals(responseCode)) {
      purchase.setPaymentStatus(PaymentStatus.COMPLETED);
      purchase.setVnpayTransactionNo(transactionNo);
      purchase.setPaymentMethod(params.get("vnp_CardType"));
      purchase.setPaidAt(OffsetDateTime.now());
      log.info("Payment completed for transaction: {}", transactionRef);
    } else {
      purchase.setPaymentStatus(PaymentStatus.FAILED);
      log.info("Payment failed for transaction: {}, code: {}", transactionRef, responseCode);
    }

    purchaseRepository.save(purchase);
    return "00".equals(responseCode);
  }

  private String buildPaymentUrl(String txnRef, Long amount, String orderInfo, String ipAddress) {
    Map<String, String> params = new TreeMap<>();
    params.put("vnp_Version", "2.1.0");
    params.put("vnp_Command", "pay");
    params.put("vnp_TmnCode", vnPayProperties.getTmnCode());
    params.put("vnp_Amount", String.valueOf(amount * 100));
    params.put("vnp_CurrCode", "VND");
    params.put("vnp_TxnRef", txnRef);
    params.put("vnp_OrderInfo", "Mua sach: " + orderInfo);
    params.put("vnp_OrderType", "other");
    params.put("vnp_Locale", "vn");
    params.put("vnp_ReturnUrl", vnPayProperties.getReturnUrl());
    params.put("vnp_IpAddr", ipAddress);
    params.put("vnp_CreateDate", LocalDateTime.now().format(VN_DATE_FORMAT));

    String hashData = buildHashData(params);
    String secureHash = hmacSHA512(vnPayProperties.getHashSecret(), hashData);

    String queryString =
        params.entrySet().stream()
            .map(
                e ->
                    URLEncoder.encode(e.getKey(), StandardCharsets.US_ASCII)
                        + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.US_ASCII))
            .collect(Collectors.joining("&"));

    return vnPayProperties.getPayUrl() + "?" + queryString + "&vnp_SecureHash=" + secureHash;
  }

  private String buildHashData(Map<String, String> fields) {
    return fields.entrySet().stream()
        .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
        .map(
            e ->
                URLEncoder.encode(e.getKey(), StandardCharsets.US_ASCII)
                    + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.US_ASCII))
        .collect(Collectors.joining("&"));
  }

  private String hmacSHA512(String key, String data) {
    try {
      Mac hmac = Mac.getInstance("HmacSHA512");
      SecretKeySpec secretKey =
          new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
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

  private String generateTransactionRef() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }
}
