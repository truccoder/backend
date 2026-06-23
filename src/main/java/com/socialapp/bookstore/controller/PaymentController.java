package com.socialapp.bookstore.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.*;

import com.socialapp.bookstore.dto.PaymentResponseDto;
import com.socialapp.bookstore.service.VNPayService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/payments")
@RequiredArgsConstructor
public class PaymentController {
  private final VNPayService vnPayService;

  @PostMapping("/books/{bookId}")
  public PaymentResponseDto createPayment(
      @PathVariable Integer bookId, HttpServletRequest request) {
    String ipAddress = getClientIp(request);
    return vnPayService.createPayment(SecurityUtils.getCurrentUserId(), bookId, ipAddress);
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
