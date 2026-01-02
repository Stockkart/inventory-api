package com.inventory.product.rest.dto.inventory;

import lombok.Data;

@Data
public class InventoryEventDto {
  private String shopId;
  private String inventoryId;
  private String productName;
  private Integer currentCount;
  private Integer threshold;
}
