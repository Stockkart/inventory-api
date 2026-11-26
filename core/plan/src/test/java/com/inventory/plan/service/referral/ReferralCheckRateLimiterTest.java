package com.inventory.plan.service.referral;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReferralCheckRateLimiterTest {

  @Test
  void blocksAfterTheLimitAndResetsNextMinute() {
    ReferralCheckRateLimiter limiter = new ReferralCheckRateLimiter();
    limiter.maxPerMinute = 3;
    limiter.clock = Clock.fixed(Instant.parse("2026-09-01T10:00:05Z"), ZoneOffset.UTC);

    for (int i = 0; i < 3; i++) {
      limiter.acquire("user:1");
    }
    assertThatThrownBy(() -> limiter.acquire("user:1"))
        .isInstanceOf(BaseException.class)
        .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((BaseException) e).getErrorCode())
            .isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
    assertThatCode(() -> limiter.acquire("user:2")).doesNotThrowAnyException();

    limiter.clock = Clock.fixed(Instant.parse("2026-09-01T10:01:00Z"), ZoneOffset.UTC);
    assertThatCode(() -> limiter.acquire("user:1")).doesNotThrowAnyException();
  }
}
