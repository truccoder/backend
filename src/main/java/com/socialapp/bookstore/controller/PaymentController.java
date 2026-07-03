package com.socialapp.bookstore.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.*;

import com.socialapp.bookstore.dto.PaymentResponseDto;
import com.socialapp.bookstore.service.PayOSService;
import com.socialapp.security.util.SecurityUtils;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/payments")
@RequiredArgsConstructor
public class PaymentController {
  private final PayOSService payOSService;

  @PostMapping("/books/{bookId}")
  public PaymentResponseDto createPayment(@PathVariable Integer bookId) {
    return payOSService.createPayment(SecurityUtils.getCurrentUserId(), bookId);
  }

  @PostMapping("/payos/webhook")
  public void handlePayOSWebhook(@RequestBody Map<String, Object> payload) {
    payOSService.handleWebhook(payload);
  }

  @PostMapping("/{transactionRef}/sync")
  public PaymentStatusResponse syncPaymentStatus(@PathVariable String transactionRef) {
    boolean paid = payOSService.syncPaymentStatus(transactionRef);
    return new PaymentStatusResponse(transactionRef, paid);
  }

  record PaymentStatusResponse(String transactionRef, boolean paid) {}
}
