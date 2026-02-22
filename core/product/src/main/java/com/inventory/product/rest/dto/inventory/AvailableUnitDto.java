package com.inventory.product.rest.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AvailableUnitDto {
  private String unit;
  private boolean baseUnit;
}
