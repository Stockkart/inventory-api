package com.inventory.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Simple metrics wrapper used across all modules.
 * Pass metric name and optional value; count and sum are calculated automatically.
 */
@Component
public class MetricsWrapper {

  private static final double[] DEFAULT_PERCENTILES = {0.5, 0.9, 0.95, 0.99};

  private final MeterRegistry registry;

  public MetricsWrapper(MeterRegistry registry) {
    this.registry = registry;
  }

  /**
   * Record a value. Increments count by 1 and adds value to sum.
   */
  public void record(String metricName, double value) {
    DistributionSummary.builder(metricName)
        .register(registry)
        .record(value);
  }

  /**
   * Record a value with tags. Format: "key1", "value1", "key2", "value2".
   */
  public void record(String metricName, double value, String... tags) {
    if (tags.length % 2 != 0) {
      throw new IllegalArgumentException("Tags must be key-value pairs");
    }
    DistributionSummary.Builder builder = DistributionSummary.builder(metricName);
    for (int i = 0; i < tags.length; i += 2) {
      builder.tag(tags[i], tags[i + 1]);
    }
    builder.register(registry).record(value);
  }

  /**
   * Record a simple count (value = 1).
   */
  public void record(String metricName) {
    record(metricName, 1.0);
  }

  /**
   * Record latency. Publishes p50, p90, p95, p99 and count (for request rate).
   */
  public <T> T recordLatency(String metricName, Supplier<T> supplier) {
    Timer.Sample sample = Timer.start(registry);
    try {
      return supplier.get();
    } finally {
      sample.stop(timer(metricName));
    }
  }

  /**
   * Record latency for void operations.
   */
  public void recordLatency(String metricName, Runnable runnable) {
    recordLatency(metricName, () -> {
      runnable.run();
      return null;
    });
  }

  /**
   * Record latency with tags.
   */
  public <T> T recordLatency(String metricName, String tagKey, String tagValue, Supplier<T> supplier) {
    Timer.Sample sample = Timer.start(registry);
    try {
      return supplier.get();
    } finally {
      sample.stop(timer(metricName, tagKey, tagValue));
    }
  }

  Timer timer(String name) {
    return Timer.builder(name)
        .description("API latency in seconds")
        .publishPercentiles(DEFAULT_PERCENTILES)
        .register(registry);
  }

  Timer timer(String name, String tagKey, String tagValue) {
    return Timer.builder(name)
        .description("API latency in seconds")
        .tag(tagKey, tagValue != null ? tagValue : "unknown")
        .publishPercentiles(DEFAULT_PERCENTILES)
        .register(registry);
  }

  public MeterRegistry getRegistry() {
    return registry;
  }
}
