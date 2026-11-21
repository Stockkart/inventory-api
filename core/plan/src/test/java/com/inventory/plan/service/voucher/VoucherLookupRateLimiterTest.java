package com.inventory.plan.service.voucher;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class VoucherLookupRateLimiterTest {

  private final VoucherLookupRateLimiter limiter = new VoucherLookupRateLimiter();

  @Test
  void theCallerIsStoppedAfterTheMissLimitUntilTheNextMinute() {
    Instant start = Instant.parse("2026-11-21T10:00:05Z");
    limiter.maxMissesPerMinute = 2;
    limiter.clock = Clock.fixed(start, ZoneOffset.UTC);

    limiter.recordMiss("shop:s1", "user:u1");
    limiter.recordMiss("shop:s1", null);

    assertThatThrownBy(() -> limiter.checkAllowed("shop:s1", "user:u2"))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getErrorCode())
        .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
    assertThatCode(() -> limiter.checkAllowed("shop:s2", "user:u1")).doesNotThrowAnyException();

    limiter.clock = Clock.fixed(start.plus(Duration.ofMinutes(1)), ZoneOffset.UTC);
    assertThatCode(() -> limiter.checkAllowed("shop:s1", null)).doesNotThrowAnyException();
  }

  @Test
  void blankIdsMakeNoKey() {
    assertThatCode(() -> limiter.checkAllowed(VoucherLookupRateLimiter.shopKey(" "),
        VoucherLookupRateLimiter.userKey(null))).doesNotThrowAnyException();
  }
}
