package com.inventory.plan.service;

import com.inventory.user.service.SeatLimitGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Adapter implementation for the user module. Delegates to {@link EntitlementGuard}.
 */
@Component
public class SeatLimitGuardImpl implements SeatLimitGuard {

  @Autowired
  private EntitlementGuard entitlementGuard;

  @Override
  public void requireSeat(String shopId) {
    entitlementGuard.requireSeat(shopId);
  }
}
