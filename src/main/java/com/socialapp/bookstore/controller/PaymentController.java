package com.socialapp.bookstore.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.*;

import com.socialapp.bookstore.dto.PaymentResponseDto;
import com.socialapp.bookstore.service.MomoService;
import com.socialapp.security.util.SecurityUtils;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/payments")
@RequiredArgsConstructor
public class PaymentController {
  private final MomoService momoService;

  @PostMapping("/books/{bookId}")
  public PaymentResponseDto createPayment(@PathVariable Integer bookId) {
    return momoService.createPayment(SecurityUtils.getCurrentUserId(), bookId);
  }

  @PostMapping("/momo/webhook")
  public void handleMomoWebhook(@RequestBody Map<String, Object> payload) {
    momoService.handleWebhook(payload);
  }

  /** Re-checks one of the caller's own purchases against MoMo. 404 for anyone else's ref. */
  @PostMapping("/{transactionRef}/sync")
  public PaymentStatusResponse syncPaymentStatus(@PathVariable String transactionRef) {
    boolean paid = momoService.syncPaymentStatus(SecurityUtils.getCurrentUserId(), transactionRef);
    return new PaymentStatusResponse(transactionRef, paid);
  }

  record PaymentStatusResponse(String transactionRef, boolean paid) {}
}
