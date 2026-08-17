package com.inventory.app.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Plain gauges for Grafana Cloud OTLP. Micrometer TimeGauges ({@code process.uptime}, {@code
 * application.ready.time}) do not show up as Prometheus {@code _seconds} series.
 */
@Component
public class ProcessAvailabilityMetrics implements MeterBinder, ApplicationListener<ApplicationReadyEvent> {

  private final AtomicReference<Double> readySeconds = new AtomicReference<Double>();

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    readySeconds.set(event.getTimeTaken().toMillis() / 1000.0);
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("process.uptime.gauge", this, ProcessAvailabilityMetrics::uptimeSeconds)
        .description("JVM uptime in seconds")
        .baseUnit("seconds")
        .register(registry);
    Gauge.builder("application.ready.gauge", this, ProcessAvailabilityMetrics::readySecondsValue)
        .description("Seconds from process start until ApplicationReady")
        .baseUnit("seconds")
        .register(registry);
  }

  private double uptimeSeconds() {
    return ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0;
  }

  private double readySecondsValue() {
    Double value = readySeconds.get();
    return value != null ? value : 0.0;
  }
}
