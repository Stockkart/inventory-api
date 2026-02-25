package com.inventory.reminders.rest.dto;

import lombok.Data;

@Data
public class InventoryLowEventDto {
  private String shopId;
  private String inventoryId;
  private String productName;
  private Integer currentCount;
  private Integer threshold;
}
