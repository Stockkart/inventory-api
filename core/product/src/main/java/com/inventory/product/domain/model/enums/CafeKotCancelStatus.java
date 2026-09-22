package com.inventory.product.domain.model.enums;

/**
 * A cancellation in flight, recorded on {@code Purchase.cafeKotCancels}. Mirrors {@code
 * CafeKotPunchStatus}: PENDING is the crash-recovery log, and nothing may
 * clear it except the cancel's own ticket write completing.
 */
public enum CafeKotCancelStatus {
  PENDING,
  COMPLETE
}
