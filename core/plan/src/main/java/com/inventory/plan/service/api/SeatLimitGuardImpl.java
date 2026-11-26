package com.inventory.plan.service.api;

import com.inventory.plan.service.EntitlementGuard;
import com.inventory.user.api.port.SeatLimitGuard;
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
