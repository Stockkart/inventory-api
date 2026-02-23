package com.inventory.pricing.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Named price rate (e.g., "Rate-A" = 100). Part of the pricing API contract. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RateDto {
  private String name;
  private BigDecimal price;
}
