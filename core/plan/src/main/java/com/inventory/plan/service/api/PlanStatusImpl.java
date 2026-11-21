package com.inventory.plan.service.api;

import com.inventory.plan.api.PlanStatus;
import com.inventory.plan.api.port.ShopPort;
import com.inventory.plan.utils.PlanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PlanStatusImpl implements PlanStatus {

  @Autowired
  private ShopPort shopPort;

  @Override
  public boolean isPlanExpired(String shopId) {
    return shopPort.getShop(shopId)
        .map(info -> PlanUtils.isExpired(info.planExpiryDate()))
        .orElse(false);
  }
}
