package com.socialapp.bookstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

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
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.socialapp.bookstore.config.MomoProperties;
import com.socialapp.common.exception.PaymentException;
import com.socialapp.common.exception.ValidationException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import reactor.core.publisher.Mono;

/**
 * Component (unit) tests for {@link MomoApiClient}, per ISTQB CTFL v4.0.1 (Section 2.2.1 component
 * testing). Covers the MoMo protocol mechanics (signature building, request shaping, HTTP error
 * translation) extracted from {@code MomoService} — these cases were previously exercised
 * indirectly through {@code MomoService}'s public methods in {@code MomoServiceTest}/{@code
 * MomoServiceErrorGuessingTest} before the split.
 *
 * <p>{@link WebClient}'s fluent chain is mocked stage-by-stage via {@link #stubResponse(Mono)} —
 * no real HTTP call to MoMo is ever made.
 */
@ExtendWith(MockitoExtension.class)
class MomoApiClientTest {

  @Mock private WebClient momoWebClient;

  @Captor private ArgumentCaptor<Map> requestBodyCaptor;

  private MomoProperties momoProperties;
  private MomoApiClient momoApiClient;

  @BeforeEach
  void setUp() {
    momoProperties = new MomoProperties();
    momoProperties.setRedirectUrl("http://localhost:3000/payment/success");
    momoProperties.setIpnUrl("http://localhost:8080/webhook");
    momoApiClient = new MomoApiClient(momoProperties, momoWebClient);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void stubResponse(Mono responseMono) {
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

  @Test
  @DisplayName("createOrderId should prefix the partner code")
  void createOrderId_shouldPrefixPartnerCode() {
    String orderId = momoApiClient.createOrderId();

    assertThat(orderId).startsWith(momoProperties.getPartnerCode());
  }

  // =====================================================================
  // requestPaymentLink
  // =====================================================================

  @Nested
  @DisplayName("requestPaymentLink")
  class RequestPaymentLinkTests {

    @Test
    @DisplayName("should return the response when MoMo accepts the link creation")
    void shouldReturnResponse_whenMomoAccepts() {
      stubResponse(Mono.just(successResponse()));

      Map<String, Object> response =
          momoApiClient.requestPaymentLink("ORDER1", "ORDER1", "1000", "Mua sach: Book", "");

      assertThat(response.get("payUrl")).isEqualTo("https://test-payment.momo.vn/pay/abc");
    }

    @Test
    @DisplayName("should reject when MoMo rejects the payment link creation")
    void shouldThrowValidationException_whenMomoRejectsLinkCreation() {
      Map<String, Object> rejected = new HashMap<>();
      rejected.put("resultCode", 99);
      stubResponse(Mono.just(rejected));

      assertThatThrownBy(
              () -> momoApiClient.requestPaymentLink("ORDER1", "ORDER1", "1000", "Book", ""))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Failed to create MoMo payment link");
    }

    @Test
    @DisplayName("should reject when MoMo returns no response body")
    void shouldThrowValidationException_whenMomoReturnsNoResponse() {
      stubResponse(Mono.empty());

      assertThatThrownBy(
              () -> momoApiClient.requestPaymentLink("ORDER1", "ORDER1", "1000", "Book", ""))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("MoMo returned no response");
    }

    @Test
    @DisplayName("should accept a String-typed resultCode from MoMo's response")
    void shouldHandleStringResultCode_fromMomoResponse() {
      Map<String, Object> response = new HashMap<>();
      response.put("resultCode", "0");
      response.put("payUrl", "https://test-payment.momo.vn/pay/xyz");
      stubResponse(Mono.just(response));

      assertThat(
              momoApiClient
                  .requestPaymentLink("ORDER1", "ORDER1", "1000", "Book", "")
                  .get("payUrl"))
          .isEqualTo("https://test-payment.momo.vn/pay/xyz");
    }

    @Test
    @DisplayName("should truncate a long order info string to 100 characters")
    void shouldTruncateLongOrderInfo() {
      stubResponse(Mono.just(successResponse()));
      String longOrderInfo = "Mua sach: " + "A".repeat(150);

      momoApiClient.requestPaymentLink("ORDER1", "ORDER1", "1000", longOrderInfo, "");

      String orderInfo = (String) requestBodyCaptor.getValue().get("orderInfo");
      assertThat(orderInfo).hasSize(100);
    }

    @Test
    @DisplayName("should not truncate a short order info string")
    void shouldNotTruncateShortOrderInfo() {
      stubResponse(Mono.just(successResponse()));

      momoApiClient.requestPaymentLink("ORDER1", "ORDER1", "1000", "Mua sach: Short Title", "");

      String orderInfo = (String) requestBodyCaptor.getValue().get("orderInfo");
      assertThat(orderInfo).isEqualTo("Mua sach: Short Title");
    }

    @ParameterizedTest(name = "requestType {0} is both sent and signed")
    @ValueSource(strings = {"captureWallet", "payWithATM"})
    @DisplayName("should send the configured request type and sign that same value")
    void shouldSendAndSignTheConfiguredRequestType(String requestType) {
      // Given: requestType is configuration now, not a constant, because a demo needs the card
      // screen (payWithATM) while anything that has to actually settle needs the wallet
      // (captureWallet) — see MomoProperties#requestType.
      momoProperties.setRequestType(requestType);
      stubResponse(Mono.just(successResponse()));

      // When
      momoApiClient.requestPaymentLink("ORDER1", "ORDER1", "1000", "Mua sach: Book", "");

      // Then: it reaches MoMo...
      Map<String, Object> body = requestBodyCaptor.getValue();
      assertThat(body.get("requestType")).isEqualTo(requestType);

      // ...and the signature covers THE SAME value. requestType is part of rawSignature, so a
      // build that signed one flow and requested another would be rejected by MoMo as a signature
      // mismatch — the one way this change could break every payment at once.
      String expectedSignature =
          hmacSHA256(
              momoProperties.getSecretKey(),
              "accessKey="
                  + momoProperties.getAccessKey()
                  + "&amount=1000"
                  + "&extraData="
                  + "&ipnUrl="
                  + momoProperties.getIpnUrl()
                  + "&orderId=ORDER1"
                  + "&orderInfo=Mua sach: Book"
                  + "&partnerCode="
                  + momoProperties.getPartnerCode()
                  + "&redirectUrl="
                  + momoProperties.getRedirectUrl()
                  + "&requestId=ORDER1"
                  + "&requestType="
                  + requestType);
      assertThat(body.get("signature")).isEqualTo(expectedSignature);
    }

    @Test
    @DisplayName("should default the request type to the wallet flow")
    void shouldDefaultToCaptureWallet() {
      // The default matters on its own: captureWallet is the only flow measured to settle on the
      // sandbox, so an unset MOMO_REQUEST_TYPE must not silently land on the one that cannot.
      stubResponse(Mono.just(successResponse()));

      momoApiClient.requestPaymentLink("ORDER1", "ORDER1", "1000", "Book", "");

      assertThat(requestBodyCaptor.getValue().get("requestType")).isEqualTo("captureWallet");
    }

    @Test
    @DisplayName("should wrap HMAC generation failure as a PaymentException")
    void shouldThrowPaymentException_whenHmacGenerationFails() {
      momoProperties.setSecretKey(null);

      assertThatThrownBy(
              () -> momoApiClient.requestPaymentLink("ORDER1", "ORDER1", "1000", "Book", ""))
          .isInstanceOf(PaymentException.class)
          .hasMessageContaining("Failed to generate HMAC");
    }

    @Test
    @DisplayName("should wrap a connection timeout as a PaymentException")
    void shouldThrowPaymentException_whenConnectionTimesOut() {
      SocketTimeoutException original = new SocketTimeoutException("Read timed out");
      stubResponse(Mono.error(original));

      // Reactor's Mono.error(...).block() wraps checked exceptions in its own
      // reactor.core.Exceptions.ReactiveException, so the original is one level deeper than
      // PaymentException's direct cause.
      assertThatThrownBy(
              () -> momoApiClient.requestPaymentLink("ORDER1", "ORDER1", "1000", "Book", ""))
          .isInstanceOf(PaymentException.class)
          .hasMessageContaining("Failed to reach MoMo")
          .rootCause()
          .isSameAs(original);
    }

    @Test
    @DisplayName("should wrap a connection refusal as a PaymentException")
    void shouldThrowPaymentException_whenConnectionIsRefused() {
      stubResponse(Mono.error(new ConnectException("Connection refused")));

      assertThatThrownBy(
              () -> momoApiClient.requestPaymentLink("ORDER1", "ORDER1", "1000", "Book", ""))
          .isInstanceOf(PaymentException.class)
          .rootCause()
          .isInstanceOf(ConnectException.class);
    }

    @Test
    @DisplayName("should wrap a 500 response as a PaymentException")
    void shouldThrowPaymentException_whenMomoReturns500() {
      stubResponse(
          Mono.error(
              WebClientResponseException.create(
                  500, "Internal Server Error", null, new byte[0], null)));

      assertThatThrownBy(
              () -> momoApiClient.requestPaymentLink("ORDER1", "ORDER1", "1000", "Book", ""))
          .isInstanceOf(PaymentException.class)
          .hasCauseInstanceOf(WebClientResponseException.class);
    }
  }

  // =====================================================================
  // queryPaymentStatus
  // =====================================================================

  @Nested
  @DisplayName("queryPaymentStatus")
  class QueryPaymentStatusTests {

    @Test
    @DisplayName("should return the raw response")
    void shouldReturnRawResponse() {
      Map<String, Object> response = new HashMap<>();
      response.put("resultCode", 0);
      response.put("transId", "TX999");
      stubResponse(Mono.just(response));

      Map<String, Object> result = momoApiClient.queryPaymentStatus("REF1");

      assertThat(result.get("transId")).isEqualTo("TX999");
      assertThat(requestBodyCaptor.getValue().get("orderId")).isEqualTo("REF1");
    }

    @Test
    @DisplayName("should reject when MoMo returns no response while polling")
    void shouldThrowValidationException_whenMomoReturnsNoResponse() {
      stubResponse(Mono.empty());

      assertThatThrownBy(() -> momoApiClient.queryPaymentStatus("REF1"))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("MoMo returned no response");
    }

    @Test
    @DisplayName("should wrap a connection timeout as a PaymentException")
    void shouldThrowPaymentException_whenConnectionTimesOut() {
      stubResponse(Mono.error(new SocketTimeoutException("Read timed out")));

      assertThatThrownBy(() -> momoApiClient.queryPaymentStatus("REF1"))
          .isInstanceOf(PaymentException.class)
          .hasMessageContaining("Failed to reach MoMo")
          .rootCause()
          .isInstanceOf(SocketTimeoutException.class);
    }
  }

  // =====================================================================
  // verifyIpnSignature
  // =====================================================================

  @Nested
  @DisplayName("verifyIpnSignature")
  class VerifyIpnSignatureTests {

    private Map<String, Object> validPayload(int resultCode, String transId) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("orderId", "REF1");
      payload.put("resultCode", resultCode);
      payload.put("transId", transId);
      payload.put("signature", computeSignature(payload));
      return payload;
    }

    private String computeSignature(Map<String, Object> payload) {
      String rawSignature =
          "accessKey="
              + momoProperties.getAccessKey()
              + "&amount="
              + ""
              + "&extraData="
              + ""
              + "&message="
              + ""
              + "&orderId="
              + payload.get("orderId")
              + "&orderInfo="
              + ""
              + "&orderType="
              + ""
              + "&partnerCode="
              + ""
              + "&payType="
              + ""
              + "&requestId="
              + ""
              + "&responseTime="
              + ""
              + "&resultCode="
              + payload.get("resultCode")
              + "&transId="
              + payload.get("transId");
      return hmacSHA256(momoProperties.getSecretKey(), rawSignature);
    }

    @Test
    @DisplayName("should return true for a correctly-signed payload")
    void shouldReturnTrue_whenSignatureMatches() {
      Map<String, Object> payload = validPayload(0, "TX1");

      assertThat(momoApiClient.verifyIpnSignature(payload)).isTrue();
    }

    @Test
    @DisplayName("should return false for a mismatched signature")
    void shouldReturnFalse_whenSignatureDoesNotMatch() {
      Map<String, Object> payload = validPayload(0, "TX1");
      payload.put("signature", "not-the-real-signature");

      assertThat(momoApiClient.verifyIpnSignature(payload)).isFalse();
    }
  }
}
