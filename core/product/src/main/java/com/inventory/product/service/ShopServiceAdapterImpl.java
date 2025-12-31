package com.inventory.product.service;

import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.user.service.ShopServiceAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ShopServiceAdapterImpl implements ShopServiceAdapter {

  @Autowired
  private ShopRepository shopRepository;

  @Override
  public String getShopName(String shopId) {
    return shopRepository.findById(shopId)
        .map(Shop::getName)
        .orElse(null);
  }

  @Override
  public boolean shopExists(String shopId) {
    return shopRepository.existsById(shopId);
  }

  @Override
  public ShopTaxInfo getShopTaxInfo(String shopId) {
    return shopRepository.findById(shopId)
        .map(shop -> new ShopServiceAdapter.ShopTaxInfo(shop.getSgst(), shop.getCgst()))
        .orElse(null);
  }
}

