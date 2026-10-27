package com.inventory.metrics.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class HttpServerExtraMetricsFilterTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final HttpServerExtraMetricsFilter filter = new HttpServerExtraMetricsFilter(registry);

  private double bucket(String le) {
    Counter c =
        registry.find(HttpServerExtraMetricsFilter.LATENCY_BUCKET).tag("le", le).counter();
    return c == null ? 0 : c.count();
  }

  @Test
  void bucketsReachTwoMinutes() {
    assertThat(HttpServerExtraMetricsFilter.LATENCY_LE).endsWith(10, 30, 60, 120);
  }

  @Test
  void aSlowReportLandsInItsBucketNotOnlyInInf() {
    filter.record("GET", "/api/v1/analytics/inventory", "200", "analytics", 45.0);

    assertThat(bucket("10")).isZero();
    assertThat(bucket("30")).isZero();
    assertThat(bucket("60")).isEqualTo(1);
    assertThat(bucket("120")).isEqualTo(1);
    assertThat(bucket("+Inf")).isEqualTo(1);
  }

  @Test
  void slowerThanTwoMinutesCountsOnlyInInf() {
    filter.record("GET", "/api/v1/mis/sales/pdf", "200", "mis", 300.0);

    assertThat(bucket("120")).isZero();
    assertThat(bucket("+Inf")).isEqualTo(1);
  }

  @Test
  void aFastRequestIsInEveryBucket() {
    filter.record("POST", "/api/v1/cart/upsert", "200", "product", 0.02);

    for (double le : HttpServerExtraMetricsFilter.LATENCY_LE) {
      String label = le == (long) le ? Long.toString((long) le) : Double.toString(le);
      assertThat(bucket(label)).as("le=%s", label).isEqualTo(1);
    }
    assertThat(bucket("+Inf")).isEqualTo(1);
  }
}
