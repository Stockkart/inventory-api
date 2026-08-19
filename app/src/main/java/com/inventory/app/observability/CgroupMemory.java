package com.inventory.app.observability;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Linux cgroup memory. Host Memory % is anonymous RSS / limit (not working set).
 */
final class CgroupMemory {

  private static final Path SYS_FS_CGROUP = Paths.get("/sys/fs/cgroup");
  private static final long UNLIMITED_THRESHOLD = 1L << 40;

  static final class Snapshot {
    final long usageBytes;
    final long workingSetBytes;
    final long anonBytes;
    final long limitBytes;

    Snapshot(long usageBytes, long workingSetBytes, long anonBytes, long limitBytes) {
      this.usageBytes = usageBytes;
      this.workingSetBytes = workingSetBytes;
      this.anonBytes = anonBytes;
      this.limitBytes = limitBytes;
    }
  }

  private CgroupMemory() {}

  static Snapshot read() {
    Snapshot v2 = readV2(SYS_FS_CGROUP);
    if (v2 != null) {
      return v2;
    }
    Snapshot v1 = readV1(SYS_FS_CGROUP.resolve("memory"));
    if (v1 != null) {
      return v1;
    }
    Path nested = nestedCgroupDir();
    if (nested != null) {
      v2 = readV2(nested);
      if (v2 != null) {
        return v2;
      }
      return readV1(nested);
    }
    return null;
  }

  static long parseStatCounter(String stat, String key) {
    if (stat == null || key == null) {
      return 0L;
    }
    String prefix = key + " ";
    String[] lines = stat.split("\n");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.startsWith(prefix)) {
        try {
          return Long.parseLong(line.substring(prefix.length()).trim());
        } catch (NumberFormatException ignored) {
          return 0L;
        }
      }
    }
    return 0L;
  }

  static long anonBytes(String stat) {
    long anon = parseStatCounter(stat, "anon");
    if (anon > 0L) {
      return anon;
    }
    long totalRss = parseStatCounter(stat, "total_rss");
    if (totalRss > 0L) {
      return totalRss;
    }
    return parseStatCounter(stat, "rss");
  }

  static long workingSet(long usageBytes, String stat) {
    long inactive = parseStatCounter(stat, "total_inactive_file");
    if (inactive <= 0L) {
      inactive = parseStatCounter(stat, "inactive_file");
    }
    long ws = usageBytes - inactive;
    return ws < 0L ? 0L : ws;
  }

  static long parseLimit(String raw) {
    if (raw == null) {
      return 0L;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty() || "max".equals(trimmed)) {
      return 0L;
    }
    try {
      long limit = Long.parseLong(trimmed);
      if (limit <= 0L || limit >= UNLIMITED_THRESHOLD) {
        return 0L;
      }
      return limit;
    } catch (NumberFormatException ignored) {
      return 0L;
    }
  }

  private static Snapshot readV2(Path dir) {
    Long usage = readLong(dir.resolve("memory.current"));
    if (usage == null) {
      return null;
    }
    String stat = readString(dir.resolve("memory.stat"));
    long limit = readLimitWalking(dir, true);
    return new Snapshot(
        usage.longValue(), workingSet(usage.longValue(), stat), anonBytes(stat), limit);
  }

  private static Snapshot readV1(Path dir) {
    Long usage = readLong(dir.resolve("memory.usage_in_bytes"));
    if (usage == null) {
      return null;
    }
    String stat = readString(dir.resolve("memory.stat"));
    long limit = readLimitWalking(dir, false);
    return new Snapshot(
        usage.longValue(), workingSet(usage.longValue(), stat), anonBytes(stat), limit);
  }

  /** Quota is often on a parent cgroup; inner memory.max is "max". */
  private static long readLimitWalking(Path dir, boolean v2) {
    Path cur = dir;
    for (int i = 0; i < 8 && cur != null; i++) {
      Path file = v2 ? cur.resolve("memory.max") : cur.resolve("memory.limit_in_bytes");
      long limit = parseLimit(readString(file));
      if (limit > 0L) {
        return limit;
      }
      if (cur.equals(SYS_FS_CGROUP)) {
        break;
      }
      cur = cur.getParent();
    }
    return 0L;
  }

  private static Path nestedCgroupDir() {
    String body = readString(Paths.get("/proc/self/cgroup"));
    if (body == null || body.isEmpty()) {
      return null;
    }
    String[] lines = body.split("\n");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      int sep = line.indexOf("::");
      if (sep >= 0) {
        String rel = line.substring(sep + 2);
        if (rel.isEmpty() || "/".equals(rel)) {
          continue;
        }
        Path dir = SYS_FS_CGROUP.resolve(rel.startsWith("/") ? rel.substring(1) : rel);
        if (Files.isDirectory(dir)) {
          return dir;
        }
      }
      if (line.contains(":memory:")) {
        int last = line.lastIndexOf(':');
        if (last >= 0 && last < line.length() - 1) {
          String rel = line.substring(last + 1);
          if (rel.startsWith("/")) {
            rel = rel.substring(1);
          }
          Path dir = SYS_FS_CGROUP.resolve("memory").resolve(rel);
          if (Files.isDirectory(dir)) {
            return dir;
          }
        }
      }
    }
    return null;
  }

  private static Long readLong(Path path) {
    String raw = readString(path);
    if (raw == null) {
      return null;
    }
    try {
      return Long.valueOf(raw.trim());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static String readString(Path path) {
    try {
      List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
      if (lines.isEmpty()) {
        return "";
      }
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < lines.size(); i++) {
        if (i > 0) {
          sb.append('\n');
        }
        sb.append(lines.get(i));
      }
      return sb.toString();
    } catch (Exception ignored) {
      return null;
    }
  }
}
