package com.inventory.pluginengine;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Pre-aggregated expiry counts from indexed extension collection. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryExpiryBucketSummary {

  /** expiryDate &lt; now */
  private int expired;

  /** 0–7 days until expiry (inclusive) */
  private int expiringWithin7Days;

  /** Within expiringSoonDays window but &gt; 7 days (configurable window) */
  private int expiringWithinSoonDays;

  private int expiringSoonDays;
}
