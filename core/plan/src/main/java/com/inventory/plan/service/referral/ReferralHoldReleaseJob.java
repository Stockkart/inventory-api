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
  /** Pages per run at most, so one run cannot loop forever when every credit keeps failing. */
  static final int MAX_PAGES = 50;

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
      int credited = 0;
      for (int page = 0; page < MAX_PAGES; page++) {
        ReferralRewardService.ReleaseRun run = rewardService.releaseDuePage(BATCH);
        credited += run.credited();
        if (run.seen() < BATCH) {
          break;
        }
      }
      if (credited > 0) {
        log.info("Credited {} referral reward(s)", credited);
      }
    } catch (RuntimeException e) {
      log.warn("Referral hold release failed: {}", e.getMessage(), e);
    }
  }
}
