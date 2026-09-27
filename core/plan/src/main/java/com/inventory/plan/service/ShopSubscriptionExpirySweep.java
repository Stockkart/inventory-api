package com.inventory.plan.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@Slf4j
public class ShopSubscriptionExpirySweep {

  @Autowired
  private ShopSubscriptionService shopSubscriptionService;

  @Scheduled(
      fixedDelayString = "${plan.subscription.expiry-sweep-interval-ms:3600000}",
      initialDelayString = "${plan.subscription.expiry-sweep-initial-delay-ms:120000}")
  public void sweep() {
    try {
      shopSubscriptionService.expireLapsed(Instant.now());
    } catch (RuntimeException e) {
      log.warn("Shop subscription expiry sweep failed: {}", e.getMessage());
    }
  }
}
