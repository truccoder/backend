package com.socialapp.bookstore.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.bookstore.dto.PaymentResponseDto;
import com.socialapp.bookstore.service.VNPayService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/payments")
@RequiredArgsConstructor
public class PaymentController {
  private final VNPayService vnPayService;

  @PostMapping("/books/{bookId}")
  public PaymentResponseDto createPayment(
      @RequestHeader("X-User-Id") Integer buyerId,
      @PathVariable Integer bookId,
      HttpServletRequest request) {
    String ipAddress = getClientIp(request);
    return vnPayService.createPayment(buyerId, bookId, ipAddress);
  }

  @GetMapping("/vnpay/callback")
  public VNPayCallbackResponse handleVNPayCallback(@RequestParam Map<String, String> params) {
    boolean success = vnPayService.handleCallback(params);
    return new VNPayCallbackResponse(success, success ? "Payment successful" : "Payment failed");
  }

  private String getClientIp(HttpServletRequest request) {
    String xForwardedFor = request.getHeader("X-Forwarded-For");
    if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
      return xForwardedFor.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }

  record VNPayCallbackResponse(boolean success, String message) {}
}
