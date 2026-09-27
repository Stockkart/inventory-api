package com.inventory.user.service;

/**
 * Checks the shop's plan allows one more member. Implemented by the plan module to avoid a
 * circular dependency; when absent, seats are not limited.
 */
public interface SeatLimitGuard {

  /**
   * Throws when adding a member to {@code shopId} would exceed its seat limit.
   */
  void requireSeat(String shopId);
}
