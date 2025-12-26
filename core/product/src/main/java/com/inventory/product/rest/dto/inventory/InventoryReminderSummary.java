package com.inventory.product.rest.dto.inventory;

import lombok.Data;

@Data
public class InventoryReminderSummary {
  private String id;
  private String name;
  private String companyName;
  private String location;
  private Integer currentCount;
}