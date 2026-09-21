package com.inventory.plugins.cafe.domain;

/**
 * A flush in flight. PENDING is the crash-recovery log: nothing may clear it except the flush
 * work completing (Task 3).
 */
public enum CafeFlushStatus {
  PENDING,
  COMPLETE
}
