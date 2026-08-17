package com.inventory.app.observability;

import com.sun.management.OperatingSystemMXBean;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.io.BufferedReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.springframework.stereotype.Component;

/**
 * Process RSS / virtual memory. DigitalOcean memory % is RSS, not JVM heap used.
 */
@Component
public class ProcessMemoryMetrics implements MeterBinder {

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("process.resident.memory", this, ProcessMemoryMetrics::rssBytes)
        .description("Resident set size (RSS); what the host reports as memory usage")
        .baseUnit("bytes")
        .register(registry);

    java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
    if (os instanceof OperatingSystemMXBean) {
      OperatingSystemMXBean sun = (OperatingSystemMXBean) os;
      Gauge.builder("process.virtual.memory", sun, OperatingSystemMXBean::getCommittedVirtualMemorySize)
          .description("Committed virtual memory")
          .baseUnit("bytes")
          .register(registry);
    }
  }

  private double rssBytes() {
    try (BufferedReader reader =
        Files.newBufferedReader(Paths.get("/proc/self/status"), StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("VmRSS:")) {
          String[] parts = line.trim().split("\\s+");
          if (parts.length >= 2) {
            return Long.parseLong(parts[1]) * 1024L;
          }
        }
      }
    } catch (Exception ignored) {
      // Non-Linux hosts have no /proc; gauge stays 0.
    }
    return 0L;
  }
}
