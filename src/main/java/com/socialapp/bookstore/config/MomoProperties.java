package com.socialapp.bookstore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Defaults are MoMo's own publicly published AIO v2 sandbox test credentials (see
 * https://github.com/momo-wallet/payment) — not secret, shared by every developer testing
 * against test-payment.momo.vn. Override via env vars once you have your own test merchant.
 */
@Data
@Component
@ConfigurationProperties(prefix = "momo")
public class MomoProperties {
  private String partnerCode = "MOMO";
  private String accessKey = "F8BBA842ECF85";
  private String secretKey = "K951B6PE1waDMi640xX08PD3vg6EkVlz";
  private String apiUrl = "https://test-payment.momo.vn";
  private String redirectUrl;
  private String ipnUrl;

  /**
   * MoMo AIO v2 {@code requestType} — which payment flow MoMo's hosted page offers the buyer.
   *
   * <p><b>{@code captureWallet}</b> (the default) is the wallet/QR flow. It is the only value
   * measured to actually settle on the sandbox, which is what makes an end-to-end run of buy →
   * redirect → sync → COMPLETED possible against real MoMo at all.
   *
   * <p><b>{@code payWithATM}</b> is the domestic card (NAPAS) flow. It gives the card-entry screen,
   * which is far easier to drive by hand during a demo than a QR code that needs a phone — but on
   * the sandbox it never finishes: five consecutive end-to-end attempts each parked at result code
   * 7002 ("đang được xử lý bởi nhà cung cấp") and none ever answered 0. Since MoMo only sends the
   * browser to {@code redirectUrl} once an order settles, choosing it means the purchase stays
   * PENDING for ever unless something else finishes the job — see the {@code dev} profile's
   * {@code POST /v1/api/payments/{transactionRef}/dev-settle}, which exists precisely so a demo can
   * use the card screen and still reach COMPLETED.
   *
   * <p>Configurable rather than hard-coded because which one is wanted depends on what is being
   * shown, not on what the code should do, and a demo should not need a rebuild to switch.
   */
  private String requestType = "captureWallet";

  /**
   * Minutes MoMo keeps a created order payable.
   *
   * <p>Must stay in step with {@code MomoService.PENDING_PAYMENT_STALE_MINUTES}, which is how long
   * a pending attempt is allowed to hold its {@code transaction_ref} before a new attempt takes it
   * over. An order that outlives that window is one a customer can still pay after this side has
   * forgotten the reference — see the comment where this is sent in {@code MomoApiClient}.
   */
  private int orderExpireMinutes = 15;
}
