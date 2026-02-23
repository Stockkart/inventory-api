package com.inventory.pricing.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Command to update pricing (partial). API contract. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PricingUpdateCommand {
  private BigDecimal additionalDiscount;
}
