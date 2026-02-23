package com.inventory.product.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.notifications.rest.dto.ReminderInventorySummary;
import com.inventory.notifications.service.InventoryAdapter;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.rest.dto.inventory.InventoryReminderSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class InventoryAdapterImpl implements InventoryAdapter {

  @Autowired
  private InventoryRepository inventoryRepository;

  @Override
  public ReminderInventorySummary getInventorySummary(String inventoryId) {

    if (inventoryId == null) {
      return null;
    }

    return inventoryRepository.findById(inventoryId).map(inv -> {
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
}
