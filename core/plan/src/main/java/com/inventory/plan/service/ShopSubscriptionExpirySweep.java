package com.inventory.plan.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Marks lapsed trial and active subscriptions EXPIRED once a day, at 00:00 IST by default.
 */
@Component
@Slf4j
public class ShopSubscriptionExpirySweep {

  @Autowired
  private ShopSubscriptionService shopSubscriptionService;

  @Scheduled(cron = "${plan.subscription.expiry-sweep.cron:0 0 0 * * *}",
      zone = "${plan.subscription.expiry-sweep.zone:Asia/Kolkata}")
  public void sweep() {
    try {
      shopSubscriptionService.expireLapsed(Instant.now());
    } catch (RuntimeException e) {
      log.warn("Shop subscription expiry sweep failed: {}", e.getMessage());
    }
  }
}
