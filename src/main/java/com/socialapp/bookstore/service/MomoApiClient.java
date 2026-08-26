package com.socialapp.bookstore.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.socialapp.bookstore.config.MomoProperties;
import com.socialapp.common.exception.PaymentException;
import com.socialapp.common.exception.ValidationException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * MoMo "AIO v2" payment gateway protocol mechanics — request/response shaping, HMAC-SHA256
 * signing, and the raw HTTP call. See {@link MomoService} for the business logic (idempotency,
 * purchase state transitions) that sits on top of this client.
 */
@Component
public class MomoApiClient {
  private final MomoProperties momoProperties;
  private final WebClient momoWebClient;

  /**
   * WAS {@code payWithATM}, THE DOMESTIC CARD (NAPAS) FLOW, AND IT COULD NOT BE COMPLETED ON THE
   * SANDBOX. A card payment run end to end there stops after the bank leg and parks the order at
   * MoMo result code 7002 — "Giao dịch đang được xử lý bởi nhà cung cấp loại hình thanh toán" —
   * which is not a final state. MoMo only sends the browser to {@code redirectUrl} once an order
   * settles, so the buyer was left sitting on MoMo's Napas callback page while the purchase row
   * stayed PENDING for ever. Measured on five consecutive attempts, every one of which answered
   * 7002 or 1000 to {@code /v2/gateway/api/query} and none 0.
   *
   * <p>{@code captureWallet} is the wallet/QR flow. It settles on the sandbox, which is what makes
   * an end-to-end test of buy → redirect → sync → COMPLETED possible at all.
   *
   * <p>THE VALUE IS PART OF THE SIGNATURE. It appears in {@code rawSignature} below as
   * {@code requestType}, so changing it here changes what is signed — which is correct, and is why
   * there is exactly one constant rather than a literal in each place.
   */
  private static final String REQUEST_TYPE = "captureWallet";

  private static final int SUCCESS_RESULT_CODE = 0;
  private static final int ORDER_INFO_MAX_LENGTH = 100;

  public MomoApiClient(
      MomoProperties momoProperties, @Qualifier("momoWebClient") WebClient momoWebClient) {
    this.momoProperties = momoProperties;
    this.momoWebClient = momoWebClient;
  }

  /**
   * A globally unique order id.
   *
   * <p>The timestamp alone was not enough: {@code transaction_ref} is UNIQUE, so two purchases
   * created in the same millisecond — different buyers, different books, nothing to do with each
   * other — produced the same id and the second one died on the constraint, surfacing to the buyer
   * as a meaningless 409. {@code orderId} is also MoMo's idempotency key, which makes a collision
   * worse than a nuisance. The random suffix removes the coincidence.
   */
  public String createOrderId() {
    return momoProperties.getPartnerCode()
        + System.currentTimeMillis()
        + "-"
        + Long.toHexString(ThreadLocalRandom.current().nextLong(0x1000000L, 0xFFFFFFFL));
  }

  public Map<String, Object> requestPaymentLink(
      String orderId, String requestId, String amount, String orderInfoRaw, String extraData) {
    String orderInfo = truncateOrderInfo(orderInfoRaw);
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

    // How long MoMo keeps this order payable, in minutes.
    //
    // Load-bearing, and not a tuning knob. MomoService reuses the single purchase row per
    // (book, buyer) and overwrites its transaction_ref once the previous pending attempt is older
    // than PENDING_PAYMENT_STALE_MINUTES. That is only safe if the old order is dead by then —
    // otherwise a customer can still pay the earlier link, MoMo sends an IPN for an orderId this
    // database no longer holds, and the money arrives with no row to credit it to.
    //
    // Nothing was sent here before, so the order lived for MoMo's own default, which is far longer
    // than that window. Sending the same number the overwrite rule uses makes the assumption true
    // by construction instead of by hope.
    //
    // Deliberately not part of rawSignature: MoMo's create-order signature is a fixed field list
    // (accessKey, amount, extraData, ipnUrl, orderId, orderInfo, partnerCode, redirectUrl,
    // requestId, requestType) and adding to it would make every request fail signature validation.
    requestBody.put("orderExpireTime", momoProperties.getOrderExpireMinutes());

    Map<String, Object> response = postForMap("/v2/gateway/api/create", requestBody);

    if (asInt(response.get("resultCode")) != SUCCESS_RESULT_CODE) {
      throw new ValidationException("Failed to create MoMo payment link");
    }

    return response;
  }

  public Map<String, Object> queryPaymentStatus(String transactionRef) {
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

    return postForMap("/v2/gateway/api/query", requestBody);
  }

  /**
   * Whether an IPN payload really came from MoMo.
   *
   * <p>Compared with {@link MessageDigest#isEqual}, which takes the same time whichever byte first
   * differs. {@code equalsIgnoreCase} returns as soon as it finds a mismatch, so how long it takes
   * leaks how much of a guessed signature was right. Over the internet against a 64-character hex
   * string that is not a practical attack — this is the cheap habit rather than a fix for something
   * exploitable, and the case-folding it replaces is preserved by lower-casing both sides first.
   */
  public boolean verifyIpnSignature(Map<String, Object> payload) {
    String computedSignature = buildIpnSignature(payload);
    String receivedSignature = String.valueOf(payload.get("signature"));

    return MessageDigest.isEqual(
        computedSignature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
        receivedSignature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
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
    return orderInfo.length() <= ORDER_INFO_MAX_LENGTH
        ? orderInfo
        : orderInfo.substring(0, ORDER_INFO_MAX_LENGTH);
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
}
