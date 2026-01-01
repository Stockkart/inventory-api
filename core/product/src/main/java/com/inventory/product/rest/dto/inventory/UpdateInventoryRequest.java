package com.inventory.product.rest.dto.inventory;

import lombok.Data;

@Data
public class UpdateInventoryRequest {
  private Integer thresholdCount; // Optional: Threshold count for low stock alerts
}

