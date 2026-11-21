package com.inventory.plan.service.order;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires unpaid orders (releasing what they hold) and retries paid orders whose fulfilment never
 * finished. Safe on several instances: every transition is conditional.
 */
@Component
@Slf4j
public class PaymentOrderMaintenanceJob {

  static final int BATCH = 200;
  /** Upper bound on expiry pages per run, so one run always ends. */
  static final int MAX_EXPIRY_PAGES = 50;
  /** A just-paid order is normally fulfilled in the same request; give it this long first. */
  static final Duration PAID_GRACE = Duration.ofMinutes(1);

  @Autowired
  private PaymentOrderStateService stateService;

  @Autowired
  private OrderReservations reservations;

  @Autowired
  private OrderFulfilmentService fulfilmentService;

  @Value("${plan.checkout.order-ttl-minutes:30}")
  long orderTtlMinutes = 30;

  @Value("${plan.checkout.claim-lease-minutes:5}")
  long claimLeaseMinutes = 5;

  Clock clock = Clock.systemUTC();

  @Scheduled(
      fixedDelayString = "${plan.checkout.maintenance-interval-ms:300000}",
      initialDelayString = "${plan.checkout.maintenance-initial-delay-ms:90000}")
  public void run() {
    try {
      expireUnpaid();
      retryStuck();
    } catch (RuntimeException e) {
      log.warn("Payment order maintenance failed: {}", e.getMessage(), e);
    }
  }

  /** Pages through expirable orders until a short page; every order it sees leaves the open states. */
  int expireUnpaid() {
    Instant now = now();
    int expired = 0;
    for (int page = 0; page < MAX_EXPIRY_PAGES; page++) {
      List<PlanPaymentOrder> expirable = stateService.findExpirable(now, Duration.ofMinutes(orderTtlMinutes), BATCH);
      for (PlanPaymentOrder order : expirable) {
        if (stateService.endUnpaid(order.getId(), PlanPaymentConstants.STATUS_EXPIRED, null, now)) {
          reservations.releaseEnded(order, now);
          expired++;
        }
      }
      if (expirable.size() < BATCH) {
        break;
      }
    }
    if (expired > 0) {
      log.info("Expired {} unpaid plan order(s)", expired);
    }
    return expired;
  }

  int retryStuck() {
    Instant now = now();
    List<PlanPaymentOrder> stuck = stateService.findStuck(now, PAID_GRACE, Duration.ofMinutes(claimLeaseMinutes), BATCH);
    for (PlanPaymentOrder order : stuck) {
      try {
        fulfilmentService.fulfil(order.getId());
      } catch (RuntimeException e) {
        log.warn("Retrying fulfilment of order {} failed: {}", order.getId(), e.getMessage());
      }
    }
    return stuck.size();
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }
}
