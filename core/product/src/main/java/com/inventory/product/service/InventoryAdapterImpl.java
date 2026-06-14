package com.inventory.product.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.reminders.rest.dto.response.ReminderExpiryBucketsResponse;
import com.inventory.reminders.rest.dto.response.ReminderInventorySummary;
import com.inventory.reminders.service.InventoryAdapter;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.pluginengine.VerticalFieldsReader;
import com.inventory.product.rest.dto.response.InventoryExpiryBucketsResponse;
import com.inventory.product.service.vertical.InventoryVerticalExpiryHandler;
import com.inventory.product.service.vertical.InventoryVerticalExtensionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class InventoryAdapterImpl implements InventoryAdapter {

  @Autowired private InventoryRepository inventoryRepository;

  @Autowired private InventoryVerticalExtensionHandler inventoryVerticalExtensionHandler;

  @Autowired private InventoryVerticalExpiryHandler inventoryVerticalExpiryHandler;

  @Override
  public ReminderInventorySummary getInventorySummary(String inventoryId) {

    if (inventoryId == null) {
      return null;
    }

    return inventoryRepository
        .findById(inventoryId)
        .map(
            inv -> {
              Map<String, Object> extensionFields =
                  inventoryVerticalExtensionHandler.loadExtensionFields(
                      inv.getShopId(), inv.getId());
              ReminderInventorySummary dto = new ReminderInventorySummary();
              dto.setId(inv.getId());
              dto.setLotId(inv.getLotId());
              dto.setName(inv.getName());
              dto.setCompanyName(inv.getCompanyName());
              dto.setLocation(inv.getLocation());
              dto.setVendorId(inv.getVendorId());
              dto.setBatchNo(VerticalFieldsReader.batchNoFrom(extensionFields));
              dto.setMaximumRetailPrice(inv.getMaximumRetailPrice());
              dto.setCostPrice(inv.getCostPrice());
              dto.setPriceToRetail(inv.getPriceToRetail());
              return dto;
            })
        .orElse(null);
  }

  @Override
  public boolean inventoryExists(String inventoryId) {
    return inventoryId != null && inventoryRepository.existsById(inventoryId);
  }

  @Override
  public ReminderExpiryBucketsResponse getExpiryBuckets(String shopId, Integer expiringSoonDays) {
    if (shopId == null) {
      return ReminderExpiryBucketsResponse.builder().expiringSoonDays(30).build();
    }
    int days = expiringSoonDays != null && expiringSoonDays > 0 ? expiringSoonDays : 30;
    InventoryExpiryBucketsResponse buckets =
        inventoryVerticalExpiryHandler.getExpiryBuckets(shopId, days);
    return ReminderExpiryBucketsResponse.builder()
        .expired(buckets.getExpired())
        .expiringWithin7Days(buckets.getExpiringWithin7Days())
        .expiringWithinSoonDays(buckets.getExpiringWithinSoonDays())
        .expiringSoonTotal(buckets.getExpiringSoonTotal())
        .totalWithExpiry(buckets.getTotalWithExpiry())
        .expiringSoonDays(buckets.getExpiringSoonDays())
        .build();
  }
}
