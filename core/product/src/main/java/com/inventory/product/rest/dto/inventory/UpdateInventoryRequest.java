package com.inventory.product.rest.dto.inventory;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateInventoryRequest {
  private Integer thresholdCount; // Optional: Threshold count for low stock alerts
  private BigDecimal additionalDiscount; // Optional: Additional discount amount
}

