package com.inventory.plan.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class ShopSubscriptionExpirySweepTest {

  @Test
  void runsOnceADayAtMidnightIst() throws NoSuchMethodException {
    Scheduled scheduled = ShopSubscriptionExpirySweep.class.getMethod("sweep").getAnnotation(Scheduled.class);

    assertEquals("${plan.subscription.expiry-sweep.cron:0 0 0 * * *}", scheduled.cron());
    assertEquals("${plan.subscription.expiry-sweep.zone:Asia/Kolkata}", scheduled.zone());
    assertEquals(-1, scheduled.fixedDelay());
    assertEquals("", scheduled.fixedDelayString());
  }
}
