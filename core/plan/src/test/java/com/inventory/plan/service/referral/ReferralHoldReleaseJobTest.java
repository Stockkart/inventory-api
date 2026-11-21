package com.inventory.plan.service.referral;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
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
    job.enabled = false;
    job.run();

    verifyNoInteractions(rewardService);
  }

  @Test
  void releasesWhenEnabledAndSwallowsFailures() {
    job.enabled = true;
    when(rewardService.releaseDuePage(anyInt())).thenThrow(new IllegalStateException("db down"));

    job.run();

    verify(rewardService).releaseDuePage(ReferralHoldReleaseJob.BATCH);
  }

  @Test
  void keepsReleasingUntilAShortPage() {
    job.enabled = true;
    when(rewardService.releaseDuePage(ReferralHoldReleaseJob.BATCH)).thenReturn(
        new ReferralRewardService.ReleaseRun(ReferralHoldReleaseJob.BATCH, 200),
        new ReferralRewardService.ReleaseRun(ReferralHoldReleaseJob.BATCH, 199),
        new ReferralRewardService.ReleaseRun(7, 7));

    job.run();

    verify(rewardService, times(3)).releaseDuePage(ReferralHoldReleaseJob.BATCH);
  }
}
