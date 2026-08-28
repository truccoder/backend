package com.socialapp.bookstore.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.bookstore.controller.PaymentController.PaymentStatusResponse;
import com.socialapp.bookstore.service.MomoService;
import com.socialapp.security.util.SecurityUtils;

import lombok.RequiredArgsConstructor;

/**
 * The one way to finish a purchase without MoMo actually collecting money. <b>Registered only
 * under the {@code dev} profile</b>; anywhere else this bean does not exist and its path 404s.
 *
 * <p>Why it is needed: MoMo's sandbox offers two flows and neither can be completed by one person
 * at a desk. {@code captureWallet} shows a QR that has to be paid from a phone running the MoMo
 * app, and {@code payWithATM} — the card-entry screen, the one that is easy to fill in by hand —
 * never settles there: five consecutive attempts each parked at result code 7002. So the buy →
 * COMPLETED path, including the author's "your book sold" notification, was not demonstrable at
 * all. Set {@code MOMO_REQUEST_TYPE=payWithATM} for the card screen, then call this to close the
 * order.
 *
 * <p>Why a separate class rather than a method on {@link PaymentController}: {@code @Profile}
 * decides whether a <i>bean</i> is created, so it cannot gate one handler method of a controller
 * that must otherwise always exist. Splitting it out is what makes "off unless the profile is
 * named" true of the endpoint itself instead of of a runtime check someone could later remove.
 *
 * <p>Why {@code dev} and not {@code !prod}: production's {@code SPRING_PROFILES_ACTIVE} is set from
 * an {@code .env} kept in the infra repo, and a missing variable there leaves it empty rather than
 * failing — see the warning at the top of {@code application-prod.yml}. Under {@code !prod} that
 * silent gap would publish this endpoint to every signed-in user, i.e. free books. Requiring the
 * profile to be named explicitly means the failure mode is "the demo endpoint is missing".
 */
@Profile("dev")
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
