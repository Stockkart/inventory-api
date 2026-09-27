package com.inventory.plan.service.order;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.repository.PlanPaymentOrderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Runs the effects of a paid order exactly once in outcome: one attempt claims the order, applies
 * every {@link OrderFulfilmentStep}, and completes it. A crashed attempt's claim goes stale after
 * the lease and the maintenance job retries; after {@link #maxAttempts} the order is parked in
 * FULFILMENT_FAILED for an operator.
 */
@Service
@Slf4j
public class OrderFulfilmentService {

  @Autowired
  private PaymentOrderStateService stateService;

  @Autowired
  private PlanPaymentOrderRepository orderRepository;

  @Autowired
  private OrderReservations reservations;

  @Autowired(required = false)
  private List<OrderFulfilmentStep> steps = new ArrayList<>();

  @Value("${plan.checkout.claim-lease-minutes:5}")
  long claimLeaseMinutes = 5;

  @Value("${plan.checkout.max-fulfilment-attempts:3}")
  int maxAttempts = 3;

  Clock clock = Clock.systemUTC();

  /** Fulfils the order if it is claimable, and returns it as it stands afterwards. */
  public PlanPaymentOrder fulfil(String orderId) {
    Instant now = now();
    Optional<PlanPaymentOrder> claimed = stateService.claim(orderId, now, lease());
    if (claimed.isEmpty()) {
      return load(orderId);
    }
    PlanPaymentOrder order = claimed.get();
    Instant claimedAt = order.getClaimedAt();

    if (order.isLatePayment() && !reservations.reacquire(order)) {
      String reason = "Paid after the order ended and its reservations could not be taken again";
      stateService.failFulfilment(orderId, claimedAt, reason, now());
      log.error("Order {} for shop {} needs an operator: {}", orderId, order.getShopId(), reason);
      return load(orderId);
    }

    try {
      for (OrderFulfilmentStep step : steps) {
        step.apply(order);
      }
    } catch (RuntimeException e) {
      String reason = describe(e);
      if (order.getFulfilmentAttempts() >= maxAttempts) {
        stateService.failFulfilment(orderId, claimedAt, reason, now());
        log.error("Order {} for shop {} failed fulfilment {} times and needs an operator: {}",
            orderId, order.getShopId(), order.getFulfilmentAttempts(), reason, e);
      } else {
        stateService.recordAttemptFailure(orderId, claimedAt, reason, now());
        log.warn("Order {} fulfilment attempt {} failed; retrying after the lease: {}",
            orderId, order.getFulfilmentAttempts(), reason, e);
      }
      return load(orderId);
    }

    if (!stateService.complete(orderId, claimedAt, now())) {
      log.warn("Order {} claim was taken over before completion; the other attempt finishes it", orderId);
    }
    return load(orderId);
  }

  private PlanPaymentOrder load(String orderId) {
    return orderRepository.findById(orderId)
        .orElseThrow(() -> new ResourceNotFoundException("Plan payment order", "id", orderId));
  }

  private Duration lease() {
    return Duration.ofMinutes(claimLeaseMinutes);
  }

  /** Millisecond precision, so the stored claim compares equal after a Mongo round trip. */
  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }

  private static String describe(RuntimeException e) {
    return e.getClass().getSimpleName() + ": " + e.getMessage();
  }
}
