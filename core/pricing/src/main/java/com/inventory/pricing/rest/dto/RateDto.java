package com.inventory.pricing.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Named price rate (e.g., "Rate-A" = 100). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RateDto {
  private String name;
  private BigDecimal price;
}
