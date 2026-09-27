package com.inventory.plan.service.referral;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReferralHoldReleaseJobTest {

  @Mock private ReferralRewardService rewardService;

  @InjectMocks
  private ReferralHoldReleaseJob job;

  @Test
  void doesNothingWhileDisabled() {
    job.run();

    verifyNoInteractions(rewardService);
  }

  @Test
  void releasesWhenEnabledAndSwallowsFailures() {
    job.enabled = true;
    when(rewardService.releaseDue(anyInt())).thenThrow(new IllegalStateException("db down"));

    job.run();

    verify(rewardService).releaseDue(ReferralHoldReleaseJob.BATCH);
  }
}
