package com.inventory.reminders.service;

import com.inventory.reminders.api.LotSummary;
import com.inventory.reminders.api.port.InventoryPort;
import com.inventory.reminders.rest.dto.response.ReminderInventorySummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Lot summaries for reminder responses, via {@link InventoryPort}. Used by {@code ReminderMapper}
 * to fill {@code ReminderDetailListResponse.inventory}.
 */
@Component
public class InventorySummaryLookup {

  @Autowired
  private InventoryPort inventoryPort;

  public ReminderInventorySummary getInventorySummary(String inventoryId) {
    LotSummary s = inventoryPort.getInventorySummary(inventoryId);
    if (s == null) {
      return null;
    }
    ReminderInventorySummary dto = new ReminderInventorySummary();
    dto.setId(s.id());
    dto.setLotId(s.lotId());
    dto.setName(s.name());
    dto.setCompanyName(s.companyName());
    dto.setLocation(s.location());
    dto.setVendorId(s.vendorId());
    dto.setBatchNo(s.batchNo());
    dto.setMaximumRetailPrice(s.maximumRetailPrice());
    dto.setCostPrice(s.costPrice());
    dto.setPriceToRetail(s.priceToRetail());
    return dto;
  }
}
