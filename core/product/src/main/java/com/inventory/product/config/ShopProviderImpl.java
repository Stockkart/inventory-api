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

  @Override
  public Optional<ReferralShop> findByReferralCode(String referralCode) {
    return shopService.findReferralShopByCode(referralCode).map(ShopProviderImpl::toReferralShop);
  }

  @Override
  public Optional<ReferralShop> getReferralShop(String shopId) {
    return shopService.getReferralShop(shopId).map(ShopProviderImpl::toReferralShop);
  }

  private static ReferralShop toReferralShop(ShopService.ShopReferralInfo info) {
    return new ReferralShop(info.shopId(), info.name(), info.referralCode(), info.contactEmail(), info.contactPhone());
  }

  @Override
  public void forEachShop(java.util.function.Consumer<ShopInfo> action) {
    shopService.forEachShopPlanInfo(
        info -> action.accept(new ShopInfo(info.shopId(), info.planId(), info.planExpiryDate())));
  }
}
