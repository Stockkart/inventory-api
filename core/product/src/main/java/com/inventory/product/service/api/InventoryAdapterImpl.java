package com.inventory.product.service.api;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.reminders.api.ExpiryBuckets;
import com.inventory.reminders.api.LotSummary;
import com.inventory.reminders.api.port.InventoryPort;
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
public class InventoryAdapterImpl implements InventoryPort {

  @Autowired private InventoryRepository inventoryRepository;

  @Autowired private InventoryVerticalExtensionHandler inventoryVerticalExtensionHandler;

  @Autowired private InventoryVerticalExpiryHandler inventoryVerticalExpiryHandler;

  @Override
  public LotSummary getInventorySummary(String inventoryId) {

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
              return new LotSummary(
                  inv.getId(),
                  inv.getLotId(),
                  inv.getName(),
                  inv.getCompanyName(),
                  inv.getLocation(),
                  inv.getVendorId(),
                  VerticalFieldsReader.batchNoFrom(extensionFields),
                  inv.getMaximumRetailPrice(),
                  inv.getCostPrice(),
                  inv.getPriceToRetail());
            })
        .orElse(null);
  }

  @Override
  public boolean inventoryExists(String inventoryId) {
    return inventoryId != null && inventoryRepository.existsById(inventoryId);
  }

  @Override
  public ExpiryBuckets getExpiryBuckets(String shopId, Integer expiringSoonDays) {
    if (shopId == null) {
      return new ExpiryBuckets(0, 0, 0, 0, 30);
    }
    int days = expiringSoonDays != null && expiringSoonDays > 0 ? expiringSoonDays : 30;
    InventoryExpiryBucketsResponse buckets =
        inventoryVerticalExpiryHandler.getExpiryBuckets(shopId, days);
    return new ExpiryBuckets(
        buckets.getExpired(),
        buckets.getExpiringWithin7Days(),
        buckets.getExpiringWithinSoonDays(),
        buckets.getExpiringSoonTotal(),
        buckets.getExpiringSoonDays());
  }
}
