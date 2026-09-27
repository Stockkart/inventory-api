package com.inventory.plan.service.referral;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Credits referral rewards whose hold has ended; a later refund claws them back (§24). Safe on several
 * instances: each reward is claimed with a conditional write.
 */
@Slf4j
@Component
public class ReferralHoldReleaseJob {

  static final int BATCH = 200;

  @Autowired
  private ReferralRewardService rewardService;

  @Value("${referral.hold-release.enabled:true}")
  boolean enabled = true;

  @Scheduled(
      fixedDelayString = "${referral.hold-release.interval-ms:3600000}",
      initialDelayString = "${referral.hold-release.initial-delay-ms:120000}")
  public void run() {
    if (!enabled) {
      return;
    }
    try {
      int credited = rewardService.releaseDue(BATCH);
      if (credited > 0) {
        log.info("Credited {} referral reward(s)", credited);
      }
    } catch (RuntimeException e) {
      log.warn("Referral hold release failed: {}", e.getMessage(), e);
    }
  }
}
