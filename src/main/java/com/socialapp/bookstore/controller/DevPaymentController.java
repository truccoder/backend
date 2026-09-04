package com.socialapp.bookstore.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.bookstore.controller.PaymentController.PaymentStatusResponse;
import com.socialapp.bookstore.service.MomoService;
import com.socialapp.security.util.SecurityUtils;

import lombok.RequiredArgsConstructor;

/**
 * The one way to finish a purchase without MoMo actually collecting money.
 *
 * <p>Why it is needed: MoMo's sandbox offers two flows and neither can be completed by one person
 * at a desk. {@code captureWallet} shows a QR that has to be paid from a phone running the MoMo
 * app, and {@code payWithATM} — the card-entry screen, the one that is easy to fill in by hand —
 * never settles there: five consecutive attempts each parked at result code 7002. So the buy →
 * COMPLETED path, including the author's "your book sold" notification, was not demonstrable at
 * all. Set {@code MOMO_REQUEST_TYPE=payWithATM} for the card screen, then call this to close the
 * order.
 *
 * <p>Registered in every profile, including production — 2026-09-04 decision to keep the demo
 * flow reachable everywhere a VPS deploy might run it, without depending on which profile name
 * happens to be active. This intentionally lets any signed-in caller settle their own purchase
 * without MoMo actually collecting money, i.e. free books. Only pull this back under
 * {@code @Profile("dev")} or an admin check if that stops being acceptable.
 */
@RestController
@RequestMapping("/v1/api/payments")
@RequiredArgsConstructor
public class DevPaymentController {
  private final MomoService momoService;

  /**
   * Marks one of the caller's own purchases paid. 404 for anyone else's ref, exactly as
   * {@code /sync}.
   *
   * <p>Answers with {@link PaymentStatusResponse}, the same shape {@code /sync} returns, so the
   * client can hand the reply to the code that already handles a poll result instead of learning a
   * second response format for a development-only route.
   */
  @PostMapping("/{transactionRef}/dev-settle")
  public PaymentStatusResponse settle(@PathVariable String transactionRef) {
    boolean paid =
        momoService.settleAsPaidForDevelopment(SecurityUtils.getCurrentUserId(), transactionRef);
    return new PaymentStatusResponse(transactionRef, paid);
  }
}
