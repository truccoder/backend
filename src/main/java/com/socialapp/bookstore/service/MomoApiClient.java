package com.socialapp.bookstore.service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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

  private static final String REQUEST_TYPE = "payWithATM";
  private static final int SUCCESS_RESULT_CODE = 0;
  private static final int ORDER_INFO_MAX_LENGTH = 100;

  public MomoApiClient(
      MomoProperties momoProperties, @Qualifier("momoWebClient") WebClient momoWebClient) {
    this.momoProperties = momoProperties;
    this.momoWebClient = momoWebClient;
  }

  public String createOrderId() {
    return momoProperties.getPartnerCode() + System.currentTimeMillis();
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

  public boolean verifyIpnSignature(Map<String, Object> payload) {
    String computedSignature = buildIpnSignature(payload);
    String receivedSignature = String.valueOf(payload.get("signature"));
    return computedSignature.equalsIgnoreCase(receivedSignature);
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
