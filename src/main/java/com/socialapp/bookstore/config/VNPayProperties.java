package com.socialapp.bookstore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "vnpay")
public class VNPayProperties {
  private String tmnCode;
  private String hashSecret;
  private String payUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
  private String returnUrl;
  private String apiUrl = "https://sandbox.vnpayment.vn/merchant_webapi/api/transaction";
}
