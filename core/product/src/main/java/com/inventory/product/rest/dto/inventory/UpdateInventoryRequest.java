package com.inventory.product.rest.dto.inventory;

import com.inventory.product.domain.model.DiscountApplicable;
import com.inventory.product.domain.model.ItemType;
import com.inventory.product.domain.model.SchemeType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class UpdateInventoryRequest {
  private Integer thresholdCount; // Optional: Threshold count for low stock alerts
  private BigDecimal additionalDiscount; // Optional: Additional discount amount
  private ItemType itemType;
  private Integer itemTypeDegree;
  private DiscountApplicable discountApplicable;
  private Instant purchaseDate;
  private SchemeType schemeType;
  private Integer scheme;
  private BigDecimal schemePercentage;
}

