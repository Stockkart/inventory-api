package com.inventory.plan.api;

import java.math.BigDecimal;

/** Plan limits on billing, checked before a sale and recorded after it. */
public interface UsageGate {

  /**
   * Checks the shop's plan allows adding a bill (billing amount and bill-count limits).
   *
   * @throws com.inventory.common.exception.ValidationException when a limit would be exceeded
   */
  void checkCanAddBill(String shopId, BigDecimal amount, int countDelta);

  /** Records a completed sale against the shop's usage for the current period. */
  void recordCompletedBill(String shopId, BigDecimal amount);
}
