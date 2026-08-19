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
 * Process RSS plus Linux cgroup memory. Host Memory % (DigitalOcean Insights, Lightsail,
 * Docker) is anonymous cgroup RSS / RAM limit — not working set, not heap used.
 */
@Component
public class ProcessMemoryMetrics implements MeterBinder {

  private volatile CgroupMemory.Snapshot cached;
  private volatile long cachedAtMs;

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("process.resident.memory", this, ProcessMemoryMetrics::rssBytes)
        .description("Process VmRSS (includes shared mappings; can exceed the container limit)")
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

    Gauge.builder("container.memory.usage", this, ProcessMemoryMetrics::cgroupUsageBytes)
        .description("cgroup memory.current (includes reclaimable file cache)")
        .baseUnit("bytes")
        .register(registry);
    Gauge.builder("container.memory.working.set", this, ProcessMemoryMetrics::cgroupWorkingSetBytes)
        .description("cgroup working set (typical host/container Memory % numerator)")
        .baseUnit("bytes")
        .register(registry);
    Gauge.builder("container.memory.anon", this, ProcessMemoryMetrics::cgroupAnonBytes)
        .description("cgroup anonymous RSS; matches typical host Memory % (excludes file cache)")
        .baseUnit("bytes")
        .register(registry);
    Gauge.builder("container.memory.limit", this, ProcessMemoryMetrics::cgroupLimitBytes)
        .description("cgroup memory.max or JVM-detected container/host RAM")
        .baseUnit("bytes")
        .register(registry);
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
      // Non-Linux hosts have no /proc; gauge is NaN.
    }
    return Double.NaN;
  }

  private double cgroupUsageBytes() {
    CgroupMemory.Snapshot snap = snapshot();
    if (snap == null || snap.usageBytes <= 0L) {
      return Double.NaN;
    }
    return snap.usageBytes;
  }

  private double cgroupWorkingSetBytes() {
    CgroupMemory.Snapshot snap = snapshot();
    if (snap == null || snap.workingSetBytes <= 0L) {
      return Double.NaN;
    }
    return snap.workingSetBytes;
  }

  private double cgroupAnonBytes() {
    CgroupMemory.Snapshot snap = snapshot();
    if (snap == null || snap.anonBytes <= 0L) {
      return Double.NaN;
    }
    return snap.anonBytes;
  }

  private double cgroupLimitBytes() {
    CgroupMemory.Snapshot snap = snapshot();
    if (snap != null && snap.limitBytes > 0L) {
      return snap.limitBytes;
    }
    java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
    if (os instanceof OperatingSystemMXBean) {
      long total = ((OperatingSystemMXBean) os).getTotalMemorySize();
      if (total > 0L && total < (1L << 40)) {
        return total;
      }
    }
    return Double.NaN;
  }

  private CgroupMemory.Snapshot snapshot() {
    long now = System.currentTimeMillis();
    CgroupMemory.Snapshot snap = cached;
    if (snap != null && now - cachedAtMs < 1000L) {
      return snap;
    }
    snap = CgroupMemory.read();
    cached = snap;
    cachedAtMs = now;
    return snap;
  }
}
