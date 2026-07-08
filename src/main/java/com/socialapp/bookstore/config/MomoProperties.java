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
}
