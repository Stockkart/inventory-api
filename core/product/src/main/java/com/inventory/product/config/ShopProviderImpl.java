package com.inventory.product.config;

import com.inventory.plan.service.ShopProvider;
import com.inventory.product.service.ShopService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Adapter implementation for plan module. Delegates to ShopService (not repository).
 */
@Component
public class ShopProviderImpl implements ShopProvider {

  @Autowired
  private ShopService shopService;

  @Override
  public Optional<ShopInfo> getShop(String shopId) {
    return shopService.getShopPlanInfo(shopId)
        .map(info -> new ShopInfo(info.shopId(), info.planId(), info.planExpiryDate()));
  }

  @Override
  public void updatePlan(String shopId, String planId, java.time.Instant expiryDate) {
    shopService.updatePlan(shopId, planId, expiryDate);
  }
}
