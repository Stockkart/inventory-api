package com.inventory.product.service;

import com.inventory.user.service.ShopServiceAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Adapter implementation for user module. Delegates to ShopService (not repository).
 */
@Component
public class ShopServiceAdapterImpl implements ShopServiceAdapter {

  @Autowired
  private ShopService shopService;

  @Override
  public String getShopName(String shopId) {
    return shopService.getShopName(shopId);
  }

  @Override
  public boolean shopExists(String shopId) {
    return shopService.shopExists(shopId);
  }

  @Override
  public ShopTaxInfo getShopTaxInfo(String shopId) {
    return shopService.getShopTaxInfo(shopId);
  }
}

