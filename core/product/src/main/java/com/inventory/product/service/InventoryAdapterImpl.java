package com.inventory.product.service;

import com.inventory.notifications.rest.dto.ReminderInventorySummary;
import com.inventory.notifications.service.InventoryAdapter;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.rest.dto.inventory.InventoryPricingDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Component
public class InventoryAdapterImpl implements InventoryAdapter {

  @Autowired
  private InventoryRepository inventoryRepository;

  @Autowired
  private InventoryPricingAdapter inventoryPricingAdapter;

  @Override
  public ReminderInventorySummary getInventorySummary(String inventoryId) {

    if (inventoryId == null) {
      return null;
    }

    return inventoryRepository.findById(inventoryId).map(inv -> {
      enrichPricing(inv);
      ReminderInventorySummary dto = new ReminderInventorySummary();
      dto.setId(inv.getId());
      dto.setLotId(inv.getLotId());
      dto.setName(inv.getName());
      dto.setCompanyName(inv.getCompanyName());
      dto.setLocation(inv.getLocation());
      dto.setVendorId(inv.getVendorId());
      dto.setBatchNo(inv.getBatchNo());
      dto.setMaximumRetailPrice(inv.getMaximumRetailPrice());
      dto.setCostPrice(inv.getCostPrice());
      dto.setSellingPrice(inv.getSellingPrice());
      return dto;
    }).orElse(null);
  }

  @Override
  public boolean inventoryExists(String inventoryId) {
    return inventoryId != null && inventoryRepository.existsById(inventoryId);
  }

  private void enrichPricing(Inventory inventory) {
    if (inventory == null || !StringUtils.hasText(inventory.getPricingId())) {
      return;
    }
    Map<String, InventoryPricingDto> pricingMap =
        inventoryPricingAdapter.getPricingBulk(List.of(inventory.getPricingId()));
    InventoryPricingDto pricing = pricingMap.get(inventory.getPricingId());
    if (pricing == null) {
      return;
    }
    inventory.setMaximumRetailPrice(pricing.getMaximumRetailPrice());
    inventory.setCostPrice(pricing.getCostPrice());
    inventory.setSellingPrice(pricing.getSellingPrice());
  }
}
