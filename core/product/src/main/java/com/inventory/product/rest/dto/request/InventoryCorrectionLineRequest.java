package com.inventory.product.rest.dto.request;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryCorrectionLineRequest {
  private String inventoryId;
  private BigDecimal requestedCurrentCount;
}

